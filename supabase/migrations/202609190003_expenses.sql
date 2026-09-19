-- Expense source records and immutable ledger posting.
-- Run after 202609190002_financial_accounts.sql.

create table if not exists public.expense_categories (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    name text not null check (length(trim(name)) > 0),
    created_at timestamptz not null default now(),
    unique (workspace_id, name)
);

create table if not exists public.expenses (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    financial_account_id uuid not null references public.financial_accounts(id) on delete restrict,
    category_id uuid not null references public.expense_categories(id) on delete restrict,
    amount numeric(20, 2) not null check (amount > 0),
    currency char(3) not null default 'PHP',
    transaction_date date not null default current_date,
    note text,
    created_by uuid not null references public.profiles(id),
    deleted_at timestamptz,
    deleted_by uuid references public.profiles(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table public.expense_categories enable row level security;
alter table public.expenses enable row level security;

drop policy if exists expense_categories_select on public.expense_categories;
drop policy if exists expenses_select on public.expenses;

create policy expense_categories_select on public.expense_categories
    for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_ledger'));

create policy expenses_select on public.expenses
    for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_ledger'));

create or replace function public.create_expense(
    requested_workspace uuid,
    requested_account uuid,
    requested_category text,
    requested_amount numeric(20, 2),
    requested_note text default null,
    requested_transaction_date date default current_date
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    new_expense_id uuid;
    category_id uuid;
    asset_ledger_id uuid;
    expense_ledger_id uuid;
    transaction_id uuid;
    account_currency char(3);
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'create_transactions') then
        raise exception 'You do not have permission to create transactions in this workspace';
    end if;

    if requested_amount is null or requested_amount <= 0 then
        raise exception 'Expense amount must be greater than zero';
    end if;

    if trim(coalesce(requested_category, '')) = '' then
        raise exception 'Expense category is required';
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

    insert into public.expense_categories (workspace_id, name)
    values (requested_workspace, trim(requested_category))
    on conflict (workspace_id, name) do update set name = excluded.name
    returning id into category_id;

    insert into public.expenses (
        workspace_id,
        financial_account_id,
        category_id,
        amount,
        currency,
        transaction_date,
        note,
        created_by
    ) values (
        requested_workspace,
        requested_account,
        category_id,
        requested_amount,
        account_currency,
        requested_transaction_date,
        nullif(trim(requested_note), ''),
        auth.uid()
    ) returning id into new_expense_id;

    insert into public.ledger_accounts (
        workspace_id,
        name,
        account_type,
        currency
    ) values (
        requested_workspace,
        'Expense: ' || trim(requested_category),
        'expense',
        account_currency
    ) on conflict (workspace_id, name) do update set name = excluded.name
    returning id into expense_ledger_id;

    insert into public.ledger_transactions (
        workspace_id,
        source_type,
        source_id,
        currency,
        created_by
    ) values (
        requested_workspace,
        'expense',
        new_expense_id,
        account_currency,
        auth.uid()
    ) returning id into transaction_id;

    insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit, credit)
    values (transaction_id, expense_ledger_id, requested_amount, 0);

    insert into public.ledger_entries (ledger_transaction_id, ledger_account_id, debit, credit)
    values (transaction_id, asset_ledger_id, 0, requested_amount);

    insert into public.audit_logs (
        workspace_id,
        actor_user_id,
        action,
        record_type,
        record_id,
        new_value
    ) values (
        requested_workspace,
        auth.uid(),
        'created',
        'expense',
        new_expense_id,
        jsonb_build_object(
            'amount', requested_amount,
            'currency', account_currency,
            'category', trim(requested_category),
            'financial_account_id', requested_account
        )
    );

    return new_expense_id;
end;
$$;

grant execute on function public.create_expense(uuid, uuid, text, numeric, text, date) to authenticated;

create index if not exists expense_categories_workspace_idx on public.expense_categories(workspace_id);
create index if not exists expenses_workspace_date_idx on public.expenses(workspace_id, transaction_date desc)
    where deleted_at is null;

-- Make the new RPC visible to PostgREST immediately after the migration runs.
notify pgrst, 'reload schema';
