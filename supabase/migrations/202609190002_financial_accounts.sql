-- Financial account creation and opening-balance posting.
-- Run this after 202609190001_initial_schema.sql.

create or replace function public.create_financial_account(
    requested_workspace uuid,
    account_name text,
    requested_account_type text,
    requested_currency char(3) default 'PHP',
    requested_starting_balance numeric(20, 2) default 0
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    new_account_id uuid;
    asset_ledger_id uuid;
    equity_ledger_id uuid;
    opening_transaction_id uuid;
    normalized_currency char(3);
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'manage_accounts') then
        raise exception 'You do not have permission to manage accounts in this workspace';
    end if;

    if trim(account_name) = '' then
        raise exception 'Account name is required';
    end if;

    if requested_starting_balance < 0 then
        raise exception 'Starting balance cannot be negative';
    end if;

    normalized_currency := upper(requested_currency);

    insert into public.financial_accounts (
        workspace_id,
        name,
        account_type,
        currency,
        starting_balance,
        owner_user_id
    ) values (
        requested_workspace,
        trim(account_name),
        trim(requested_account_type),
        normalized_currency,
        requested_starting_balance,
        auth.uid()
    ) returning id into new_account_id;

    insert into public.ledger_accounts (
        workspace_id,
        financial_account_id,
        name,
        account_type,
        currency
    ) values (
        requested_workspace,
        new_account_id,
        trim(account_name),
        'asset',
        normalized_currency
    ) returning id into asset_ledger_id;

    insert into public.ledger_accounts (
        workspace_id,
        name,
        account_type,
        currency
    ) values (
        requested_workspace,
        'Opening Balance Equity',
        'equity',
        normalized_currency
    ) on conflict (workspace_id, name) do update
        set name = excluded.name
    returning id into equity_ledger_id;

    if requested_starting_balance > 0 then
        insert into public.ledger_transactions (
            workspace_id,
            source_type,
            source_id,
            currency,
            created_by
        ) values (
            requested_workspace,
            'financial_account_opening_balance',
            new_account_id,
            normalized_currency,
            auth.uid()
        ) returning id into opening_transaction_id;

        insert into public.ledger_entries (
            ledger_transaction_id,
            ledger_account_id,
            debit
        ) values (opening_transaction_id, asset_ledger_id, requested_starting_balance);

        insert into public.ledger_entries (
            ledger_transaction_id,
            ledger_account_id,
            credit
        ) values (opening_transaction_id, equity_ledger_id, requested_starting_balance);
    end if;

    return new_account_id;
end;
$$;

grant execute on function public.create_financial_account(uuid, text, text, char(3), numeric) to authenticated;
