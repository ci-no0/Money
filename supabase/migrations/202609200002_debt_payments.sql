-- Partial debt payments with deterministic installment allocation.
-- Run after 202609200001_debts.sql.

create or replace function public.create_debt_payment(
    requested_workspace uuid,
    requested_debt uuid,
    requested_account uuid,
    requested_amount numeric(20, 2)
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    new_payment_id uuid;
    ledger_transaction_id uuid;
    debt_direction text;
    debt_title text;
    debt_currency char(3);
    account_currency char(3);
    asset_ledger_id uuid;
    debt_ledger_id uuid;
    interest_ledger_id uuid;
    outstanding numeric(20, 2);
    payment_remaining numeric(20, 2);
    installment_remaining numeric(20, 2);
    allocation_amount numeric(20, 2);
    required_amount numeric(20, 2);
    existing_principal numeric(20, 2);
    existing_interest numeric(20, 2);
    existing_fee numeric(20, 2);
    principal_due numeric(20, 2);
    interest_due numeric(20, 2);
    fee_due numeric(20, 2);
    allocated_principal numeric(20, 2) := 0;
    allocated_interest numeric(20, 2) := 0;
    allocated_fee numeric(20, 2) := 0;
    schedule_record record;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;
    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'manage_debts') then
        raise exception 'You do not have permission to manage debts in this workspace';
    end if;
    if requested_amount is null or requested_amount <= 0 then
        raise exception 'Payment amount must be greater than zero';
    end if;

    select d.direction, d.title, d.currency
      into debt_direction, debt_title, debt_currency
      from public.debts d
     where d.id = requested_debt
       and d.workspace_id = requested_workspace
       and d.deleted_at is null
       and d.status <> 'cancelled';
    if debt_direction is null then
        raise exception 'Debt was not found or is cancelled';
    end if;

    select fa.currency, la.id
      into account_currency, asset_ledger_id
      from public.financial_accounts fa
      join public.ledger_accounts la on la.financial_account_id = fa.id
     where fa.id = requested_account
       and fa.workspace_id = requested_workspace
       and fa.is_active
       and fa.deleted_at is null
       and la.account_type = 'asset';
    if asset_ledger_id is null then
        raise exception 'Financial account was not found or is inactive';
    end if;
    if account_currency <> debt_currency then
        raise exception 'Payment account currency does not match the debt currency';
    end if;

    select coalesce(sum(s.required_amount - coalesce(a.allocated_amount, 0)), 0)
      into outstanding
      from public.installment_schedules s
      left join (
          select installment_id, sum(principal_amount + interest_amount + fee_amount) as allocated_amount
          from public.payment_allocations
          group by installment_id
      ) a on a.installment_id = s.id
     where s.debt_id = requested_debt;

    if requested_amount > outstanding then
        raise exception 'Payment exceeds the remaining debt balance';
    end if;

    insert into public.payments (
        workspace_id, debt_id, financial_account_id, amount, currency, created_by
    ) values (
        requested_workspace, requested_debt, requested_account, requested_amount, debt_currency, auth.uid()
    ) returning id into new_payment_id;

    payment_remaining := requested_amount;

    for schedule_record in
        select s.id, s.required_amount, s.principal_amount, s.interest_amount, s.fee_amount
        from public.installment_schedules s
        where s.debt_id = requested_debt
        order by s.installment_number
    loop
        exit when payment_remaining <= 0;

        select coalesce(sum(pa.principal_amount), 0),
               coalesce(sum(pa.interest_amount), 0),
               coalesce(sum(pa.fee_amount), 0)
          into existing_principal, existing_interest, existing_fee
          from public.payment_allocations pa
         where pa.installment_id = schedule_record.id;

        required_amount := schedule_record.required_amount;
        installment_remaining := required_amount - existing_principal - existing_interest - existing_fee;
        if installment_remaining <= 0 then
            continue;
        end if;

        allocation_amount := least(payment_remaining, installment_remaining);
        principal_due := greatest(schedule_record.principal_amount - existing_principal, 0);
        interest_due := greatest(schedule_record.interest_amount - existing_interest, 0);
        fee_due := greatest(schedule_record.fee_amount - existing_fee, 0);

        principal_due := least(allocation_amount, principal_due);
        allocation_amount := allocation_amount - principal_due;
        interest_due := least(allocation_amount, interest_due);
        allocation_amount := allocation_amount - interest_due;
        fee_due := least(allocation_amount, fee_due);

        insert into public.payment_allocations (
            payment_id, installment_id, principal_amount, interest_amount, fee_amount
        ) values (
            new_payment_id,
            schedule_record.id,
            principal_due,
            interest_due,
            fee_due
        );

        allocated_principal := allocated_principal + principal_due;
        allocated_interest := allocated_interest + interest_due;
        allocated_fee := allocated_fee + fee_due;
        payment_remaining := payment_remaining - principal_due - interest_due - fee_due;
    end loop;

    if payment_remaining > 0 then
        raise exception 'Payment could not be fully allocated';
    end if;

    update public.installment_schedules s
       set status = case
           when coalesce((select sum(pa.principal_amount + pa.interest_amount + pa.fee_amount)
                          from public.payment_allocations pa where pa.installment_id = s.id), 0) >= s.required_amount
               then 'paid'::public.payment_status
           when coalesce((select sum(pa.principal_amount + pa.interest_amount + pa.fee_amount)
                          from public.payment_allocations pa where pa.installment_id = s.id), 0) > 0
               then 'partially_paid'::public.payment_status
           else s.status
       end
     where s.debt_id = requested_debt;

    update public.debts
       set status = case when not exists (
           select 1 from public.installment_schedules s
           where s.debt_id = requested_debt and s.status <> 'paid'
       ) then 'paid'::public.debt_status else 'active'::public.debt_status end,
           updated_at = now()
     where id = requested_debt;

    insert into public.ledger_accounts (workspace_id, name, account_type, currency)
    values (
        requested_workspace,
        'Debt: ' || debt_title || ' [' || requested_debt || ']',
        case when debt_direction = 'payable' then 'liability'::public.ledger_account_type
             else 'asset'::public.ledger_account_type end,
        debt_currency
    ) on conflict (workspace_id, name) do update
        set name = excluded.name
    returning id into debt_ledger_id;

    if allocated_interest + allocated_fee > 0 then
        insert into public.ledger_accounts (workspace_id, name, account_type, currency)
        values (
            requested_workspace,
            case when debt_direction = 'payable' then 'Interest and Fees Expense' else 'Interest Income' end,
            case when debt_direction = 'payable' then 'expense'::public.ledger_account_type
                 else 'revenue'::public.ledger_account_type end,
            debt_currency
        ) on conflict (workspace_id, name) do update set name = excluded.name
        returning id into interest_ledger_id;
    end if;

    insert into public.ledger_transactions (workspace_id, source_type, source_id, currency, created_by)
    values (requested_workspace, 'debt_payment', new_payment_id, debt_currency, auth.uid())
    returning id into ledger_transaction_id;

    if debt_direction = 'payable' then
        if allocated_principal > 0 then
            insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit)
            values (ledger_transaction_id, debt_ledger_id, allocated_principal);
        end if;
        if allocated_interest + allocated_fee > 0 then
            insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit)
            values (ledger_transaction_id, interest_ledger_id, allocated_interest + allocated_fee);
        end if;
        insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, credit)
        values (ledger_transaction_id, asset_ledger_id, requested_amount);
    else
        insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit)
        values (ledger_transaction_id, asset_ledger_id, requested_amount);
        if allocated_principal > 0 then
            insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, credit)
            values (ledger_transaction_id, debt_ledger_id, allocated_principal);
        end if;
        if allocated_interest + allocated_fee > 0 then
            insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, credit)
            values (ledger_transaction_id, interest_ledger_id, allocated_interest + allocated_fee);
        end if;
    end if;

    insert into public.audit_logs (workspace_id, actor_user_id, action, record_type, record_id, new_value)
    values (
        requested_workspace, auth.uid(), 'created', 'debt_payment', new_payment_id,
        jsonb_build_object('debt_id', requested_debt, 'amount', requested_amount,
                           'principal', allocated_principal, 'interest', allocated_interest,
                           'fees', allocated_fee)
    );

    return new_payment_id;
end;
$$;

grant execute on function public.create_debt_payment(uuid, uuid, uuid, numeric) to authenticated;

drop policy if exists payments_insert on public.payments;
create policy payments_insert on public.payments for insert
    with check (public.has_workspace_permission(auth.uid(), workspace_id, 'manage_debts'));

drop policy if exists allocations_insert on public.payment_allocations;
create policy allocations_insert on public.payment_allocations for insert
    with check (exists (
        select 1 from public.payments p
        where p.id = payment_id
          and public.has_workspace_permission(auth.uid(), p.workspace_id, 'manage_debts')
    ));

notify pgrst, 'reload schema';
