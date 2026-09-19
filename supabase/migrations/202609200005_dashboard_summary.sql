-- Dashboard account balances and totals derived from the posted ledger.
-- Run after 202609190003_expenses.sql and the debt-payment migrations.

create or replace function public.get_account_balance(requested_account uuid)
returns numeric(20, 2)
language plpgsql
security definer
set search_path = public
as $$
declare
    account_workspace_id uuid;
    computed_balance numeric(20, 2);
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    select fa.workspace_id
      into account_workspace_id
      from public.financial_accounts fa
     where fa.id = requested_account
       and fa.deleted_at is null;

    if account_workspace_id is null then
        raise exception 'Financial account was not found';
    end if;

    if not public.has_workspace_permission(auth.uid(), account_workspace_id, 'view_accounts') then
        raise exception 'You do not have permission to view this account';
    end if;

    select coalesce(sum(case when la.account_type = 'asset' then le.debit - le.credit else 0 end), 0)
      into computed_balance
      from public.ledger_accounts la
      left join public.ledger_entries le on le.ledger_account_id = la.id
     where la.financial_account_id = requested_account;

    return computed_balance;
end;
$$;

grant execute on function public.get_account_balance(uuid) to authenticated;

drop function if exists public.get_workspace_dashboard(uuid);

create function public.get_workspace_dashboard(requested_workspace uuid)
returns table (
    total_cash numeric(20, 2),
    total_debts numeric(20, 2),
    total_expenses numeric(20, 2),
    net_worth numeric(20, 2),
    cash_flow numeric(20, 2)
)
language plpgsql
security definer
set search_path = public
as $$
declare
    cash_total numeric(20, 2) := 0;
    debt_total numeric(20, 2) := 0;
    expense_total numeric(20, 2) := 0;
    flow_total numeric(20, 2) := 0;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'view_ledger') then
        raise exception 'You do not have permission to view this workspace dashboard';
    end if;

    select coalesce(sum(public.get_account_balance(fa.id)), 0)
      into cash_total
      from public.financial_accounts fa
     where fa.workspace_id = requested_workspace
       and fa.is_active
       and fa.deleted_at is null;

    select coalesce(sum(d.total_payable), 0)
      into debt_total
      from public.debts d
     where d.workspace_id = requested_workspace
       and d.deleted_at is null
       and d.status <> 'cancelled';

    select coalesce(sum(e.amount), 0)
      into expense_total
      from public.expenses e
     where e.workspace_id = requested_workspace
       and e.deleted_at is null;

    select coalesce(sum(
        case
            when le.debit > 0 then -le.debit
            when le.credit > 0 then le.credit
            else 0
        end
    ), 0)
      into flow_total
      from public.ledger_transactions lt
      join public.ledger_entries le on le.ledger_transaction_id = lt.id
      join public.ledger_accounts la on la.id = le.ledger_account_id
     where lt.workspace_id = requested_workspace
       and la.account_type = 'asset'
       and lt.posted_at >= date_trunc('month', current_date);

    return query
    select cash_total, debt_total, expense_total, cash_total - debt_total, flow_total;
end;
$$;

grant execute on function public.get_workspace_dashboard(uuid) to authenticated;

notify pgrst, 'reload schema';
