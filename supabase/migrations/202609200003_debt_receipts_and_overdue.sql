-- Initial debt/loan funding and server-date payment status refresh.
-- Run after 202609200002_debt_payments.sql.

create or replace function public.post_debt_receipt(
    requested_workspace uuid,
    requested_debt uuid,
    requested_account uuid
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    debt_record record;
    account_currency char(3);
    asset_ledger_id uuid;
    debt_ledger_id uuid;
    ledger_transaction_id uuid;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;
    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'manage_debts') then
        raise exception 'You do not have permission to manage debts in this workspace';
    end if;

    select d.id, d.direction, d.title, d.original_amount, d.currency
      into debt_record
      from public.debts d
     where d.id = requested_debt
       and d.workspace_id = requested_workspace
       and d.deleted_at is null
       and d.status <> 'cancelled';
    if debt_record.id is null then
        raise exception 'Debt was not found or is cancelled';
    end if;

    if exists (
        select 1 from public.ledger_transactions lt
        where lt.source_type = 'debt_receipt'
          and lt.source_id = requested_debt
    ) then
        raise exception 'Debt receipt has already been posted';
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
    if account_currency <> debt_record.currency then
        raise exception 'Receipt account currency does not match the debt currency';
    end if;

    insert into public.ledger_accounts (workspace_id, name, account_type, currency)
    values (
        requested_workspace,
        'Debt: ' || debt_record.title || ' [' || requested_debt || ']',
        case when debt_record.direction = 'payable' then 'liability'::public.ledger_account_type
             else 'asset'::public.ledger_account_type end,
        debt_record.currency
    ) on conflict (workspace_id, name) do update
        set name = excluded.name
    returning id into debt_ledger_id;

    insert into public.ledger_transactions (
        workspace_id, source_type, source_id, currency, created_by
    ) values (
        requested_workspace, 'debt_receipt', requested_debt, debt_record.currency, auth.uid()
    ) returning id into ledger_transaction_id;

    if debt_record.direction = 'payable' then
        insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit)
        values (ledger_transaction_id, asset_ledger_id, debt_record.original_amount);
        insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, credit)
        values (ledger_transaction_id, debt_ledger_id, debt_record.original_amount);
    else
        insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit)
        values (ledger_transaction_id, debt_ledger_id, debt_record.original_amount);
        insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, credit)
        values (ledger_transaction_id, asset_ledger_id, debt_record.original_amount);
    end if;

    insert into public.audit_logs (
        workspace_id, actor_user_id, action, record_type, record_id, new_value
    ) values (
        requested_workspace, auth.uid(), 'posted', 'debt_receipt', requested_debt,
        jsonb_build_object('financial_account_id', requested_account,
                           'amount', debt_record.original_amount,
                           'currency', debt_record.currency)
    );

    return ledger_transaction_id;
end;
$$;

grant execute on function public.post_debt_receipt(uuid, uuid, uuid) to authenticated;

create or replace function public.refresh_debt_payment_statuses(requested_workspace uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;
    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'view_debts') then
        raise exception 'You do not have permission to view debts in this workspace';
    end if;

    update public.installment_schedules s
       set status = case
           when s.status in ('paid', 'cancelled', 'partially_paid') then s.status
           when s.due_date < current_date then 'overdue'::public.payment_status
           else 'due'::public.payment_status
       end
      from public.debts d
     where d.id = s.debt_id
       and d.workspace_id = requested_workspace
       and d.deleted_at is null
       and d.status <> 'cancelled';
end;
$$;

grant execute on function public.refresh_debt_payment_statuses(uuid) to authenticated;

notify pgrst, 'reload schema';
