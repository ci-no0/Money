-- Budget tracking for monthly category limits.
-- Run after the expense categories migration and the dashboard summary migration.

-- Re-runnable migration support: drop existing objects from earlier attempts first.
drop policy if exists budgets_select on public.budgets;
drop policy if exists budgets_insert on public.budgets;
drop policy if exists budgets_update on public.budgets;
drop function if exists public.get_workspace_budget_overview(uuid);
drop function if exists public.create_budget(uuid, text, text, numeric, date);
drop table if exists public.budgets cascade;

create table public.budgets (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    name text not null check (length(trim(name)) > 0),
    category text not null check (length(trim(category)) > 0),
    amount numeric(20, 2) not null check (amount > 0),
    currency char(3) not null default 'PHP',
    budget_month date not null default (date_trunc('month', current_date))::date,
    created_by uuid not null references public.profiles(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (workspace_id, category, budget_month)
);

alter table public.budgets enable row level security;

create policy budgets_select on public.budgets
    for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_ledger'));

create policy budgets_insert on public.budgets
    for insert with check (public.has_workspace_permission(auth.uid(), workspace_id, 'create_transactions'));

create policy budgets_update on public.budgets
    for update using (public.has_workspace_permission(auth.uid(), workspace_id, 'create_transactions'))
    with check (public.has_workspace_permission(auth.uid(), workspace_id, 'create_transactions'));

create or replace function public.create_budget(
    requested_workspace uuid,
    requested_name text,
    requested_category text,
    requested_amount numeric(20, 2),
    requested_budget_month date default (date_trunc('month', current_date))::date
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    normalized_month date;
    new_budget_id uuid;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'create_transactions') then
        raise exception 'You do not have permission to create budgets in this workspace';
    end if;

    if trim(coalesce(requested_name, '')) = '' then
        raise exception 'Budget name is required';
    end if;

    if trim(coalesce(requested_category, '')) = '' then
        raise exception 'Budget category is required';
    end if;

    if requested_amount is null or requested_amount <= 0 then
        raise exception 'Budget amount must be greater than zero';
    end if;

    normalized_month := date_trunc('month', requested_budget_month)::date;

    insert into public.budgets (
        workspace_id,
        name,
        category,
        amount,
        currency,
        budget_month,
        created_by
    ) values (
        requested_workspace,
        trim(requested_name),
        trim(requested_category),
        requested_amount,
        (select base_currency from public.workspaces where id = requested_workspace),
        normalized_month,
        auth.uid()
    )
    on conflict (workspace_id, category, budget_month)
    do update set
        name = excluded.name,
        amount = excluded.amount,
        currency = excluded.currency,
        updated_at = now()
    returning id into new_budget_id;

    return new_budget_id;
end;
$$;

grant execute on function public.create_budget(uuid, text, text, numeric, date) to authenticated;

create or replace function public.get_workspace_budget_overview(requested_workspace uuid)
returns table (
    budget_name text,
    category text,
    budget_amount numeric(20, 2),
    spent_amount numeric(20, 2),
    remaining_amount numeric(20, 2),
    percent_used numeric(20, 2)
)
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'view_ledger') then
        raise exception 'You do not have permission to view budget data for this workspace';
    end if;

    return query
    with budget_rows as (
        select
            b.id,
            b.name as budget_name,
            b.category,
            b.amount as budget_amount,
            b.budget_month,
            coalesce(sum(e.amount), 0) as spent_amount
        from public.budgets b
        left join public.expense_categories ec
            on lower(trim(ec.name)) = lower(trim(b.category))
           and ec.workspace_id = b.workspace_id
        left join public.expenses e
            on e.category_id = ec.id
           and e.workspace_id = requested_workspace
           and e.transaction_date >= date_trunc('month', b.budget_month)::date
           and e.transaction_date < (date_trunc('month', b.budget_month) + interval '1 month')::date
        where b.workspace_id = requested_workspace
        group by b.id, b.name, b.category, b.amount, b.budget_month
    )
    select
        br.budget_name,
        br.category,
        br.budget_amount,
        br.spent_amount,
        br.budget_amount - br.spent_amount as remaining_amount,
        case
            when br.budget_amount = 0 then 0
            else round((br.spent_amount / br.budget_amount) * 100, 2)
        end as percent_used
    from budget_rows br
    order by br.budget_month desc, br.category;
end;
$$;

grant execute on function public.get_workspace_budget_overview(uuid) to authenticated;

notify pgrst, 'reload schema';
