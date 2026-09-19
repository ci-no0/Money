-- Audited payment reversal using compensating ledger entries.
-- Run after 202609200003_debt_receipts_and_overdue.sql.

create or replace function public.reverse_debt_payment(
    requested_workspace uuid,
    requested_payment uuid
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    payment_record record;
    original_transaction_id uuid;
    reversal_transaction_id uuid;
    existing_reversal_id uuid;
    entry_record record;
    allocated_amount numeric(20, 2);
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;
    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'manage_debts') then
        raise exception 'You do not have permission to manage debts in this workspace';
    end if;

    select p.id, p.debt_id, p.amount, p.currency, p.reversed_at
      into payment_record
      from public.payments p
     where p.id = requested_payment
       and p.workspace_id = requested_workspace;
    if payment_record.id is null then
        raise exception 'Payment was not found';
    end if;

    select lt.id
      into original_transaction_id
      from public.ledger_transactions lt
     where lt.source_type = 'debt_payment'
       and lt.source_id = requested_payment;
    if original_transaction_id is null then
        raise exception 'Payment ledger transaction was not found';
    end if;

    select lt.id
      into existing_reversal_id
      from public.ledger_transactions lt
     where lt.source_type = 'debt_payment_reversal'
       and lt.source_id = requested_payment;
    if existing_reversal_id is not null then
        return existing_reversal_id;
    end if;

    if payment_record.reversed_at is not null then
        raise exception 'Payment is already reversed';
    end if;

    insert into public.ledger_transactions (
        workspace_id, source_type, source_id, currency, created_by, reversal_of
    ) values (
        requested_workspace, 'debt_payment_reversal', requested_payment,
        payment_record.currency, auth.uid(), original_transaction_id
    ) returning id into reversal_transaction_id;

    for entry_record in
        select ledger_account_id, debit, credit
        from public.ledger_entries
        where ledger_transaction_id = original_transaction_id
    loop
        insert into public.ledger_entries (
            ledger_transaction_id, ledger_account_id, debit, credit
        ) values (
            reversal_transaction_id,
            entry_record.ledger_account_id,
            entry_record.credit,
            entry_record.debit
        );
    end loop;

    update public.payments
       set reversed_at = now(), reversed_by = auth.uid()
     where id = requested_payment;

    update public.installment_schedules s
       set status = case
           when coalesce((select sum(pa.principal_amount + pa.interest_amount + pa.fee_amount)
                          from public.payment_allocations pa
                          join public.payments p on p.id = pa.payment_id
                          where pa.installment_id = s.id and p.reversed_at is null), 0) >= s.required_amount
               then 'paid'::public.payment_status
           when coalesce((select sum(pa.principal_amount + pa.interest_amount + pa.fee_amount)
                          from public.payment_allocations pa
                          join public.payments p on p.id = pa.payment_id
                          where pa.installment_id = s.id and p.reversed_at is null), 0) > 0
               then 'partially_paid'::public.payment_status
           when s.due_date < current_date then 'overdue'::public.payment_status
           else 'upcoming'::public.payment_status
       end
      from public.debts d
     where s.debt_id = d.id
       and d.id = payment_record.debt_id;

    select coalesce(sum(pa.principal_amount + pa.interest_amount + pa.fee_amount), 0)
      into allocated_amount
      from public.payment_allocations pa
      join public.payments p on p.id = pa.payment_id
     where pa.installment_id in (
         select id from public.installment_schedules where debt_id = payment_record.debt_id
     )
       and p.reversed_at is null;

    update public.debts
       set status = case when allocated_amount >= (
           select total_payable from public.debts where id = payment_record.debt_id
       ) then 'paid'::public.debt_status else 'active'::public.debt_status end,
           updated_at = now()
     where id = payment_record.debt_id;

    insert into public.audit_logs (
        workspace_id, actor_user_id, action, record_type, record_id, previous_value, new_value
    ) values (
        requested_workspace, auth.uid(), 'reversed', 'debt_payment', requested_payment,
        jsonb_build_object('amount', payment_record.amount),
        jsonb_build_object('reversal_transaction_id', reversal_transaction_id)
    );

    return reversal_transaction_id;
end;
$$;

grant execute on function public.reverse_debt_payment(uuid, uuid) to authenticated;

notify pgrst, 'reload schema';
