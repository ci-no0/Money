-- Money & Debt Tracker initial Supabase schema
-- Run once in the Supabase SQL Editor.
-- This migration creates the security and financial foundations.
-- Do not put a service_role key in the Android app.

create extension if not exists pgcrypto;

create type public.workspace_type as enum ('personal', 'family', 'business');
create type public.member_status as enum ('active', 'invited', 'suspended');
create type public.permission_effect as enum ('allow', 'deny');
create type public.ledger_account_type as enum ('asset', 'liability', 'equity', 'revenue', 'expense');
create type public.debt_status as enum ('pending', 'active', 'paid', 'cancelled');
create type public.payment_status as enum ('upcoming', 'due', 'partially_paid', 'paid', 'overdue', 'cancelled');
create type public.sync_status as enum ('pending', 'processing', 'synced', 'conflict', 'rejected');

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text,
    timezone text not null default 'Asia/Manila',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.workspaces (
    id uuid primary key default gen_random_uuid(),
    name text not null check (length(trim(name)) > 0),
    workspace_type public.workspace_type not null,
    base_currency char(3) not null default 'PHP',
    timezone text not null default 'Asia/Manila',
    created_by uuid not null references public.profiles(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.roles (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    name text not null,
    is_admin boolean not null default false,
    created_at timestamptz not null default now(),
    unique (workspace_id, name)
);

create table public.permissions (
    code text primary key,
    description text not null
);

insert into public.permissions (code, description) values
    ('view_workspace', 'View authorized workspace data'),
    ('manage_users', 'Manage workspace members and permissions'),
    ('view_accounts', 'View financial accounts'),
    ('manage_accounts', 'Create and edit financial accounts'),
    ('view_ledger', 'View posted financial records'),
    ('create_transactions', 'Create source transactions'),
    ('edit_transactions', 'Edit unposted source transactions'),
    ('delete_transactions', 'Soft-delete source transactions'),
    ('view_debts', 'View debts and payment schedules'),
    ('manage_debts', 'Create and manage debts and payments'),
    ('manage_reports', 'View reports and analytics'),
    ('manage_attachments', 'Upload and view attachments')
on conflict (code) do nothing;

create table public.workspace_members (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    role_id uuid references public.roles(id) on delete set null,
    status public.member_status not null default 'active',
    created_at timestamptz not null default now(),
    unique (workspace_id, user_id)
);

create table public.role_permissions (
    role_id uuid not null references public.roles(id) on delete cascade,
    permission_code text not null references public.permissions(code),
    primary key (role_id, permission_code)
);

create table public.member_permissions (
    member_id uuid not null references public.workspace_members(id) on delete cascade,
    permission_code text not null references public.permissions(code),
    effect public.permission_effect not null,
    primary key (member_id, permission_code)
);

create or replace function public.has_workspace_permission(
    requested_user uuid,
    requested_workspace uuid,
    requested_permission text
) returns boolean
language sql
security definer
stable
set search_path = public
as $$
    select exists (
        select 1
        from public.workspaces w
        where w.id = requested_workspace
          and w.created_by = requested_user
    )
    or exists (
        select 1
        from public.workspace_members wm
        left join public.roles r on r.id = wm.role_id
        where wm.workspace_id = requested_workspace
          and wm.user_id = requested_user
          and wm.status = 'active'
          and coalesce(r.is_admin, false)
          and not exists (
              select 1
              from public.member_permissions mp_deny
              where mp_deny.member_id = wm.id
                and mp_deny.permission_code = requested_permission
                and mp_deny.effect = 'deny'
          )
    )
    or exists (
        select 1
        from public.workspace_members wm
        left join public.role_permissions rp on rp.role_id = wm.role_id
        where wm.workspace_id = requested_workspace
          and wm.user_id = requested_user
          and wm.status = 'active'
          and rp.permission_code = requested_permission
          and not exists (
              select 1
              from public.member_permissions mp_deny
              where mp_deny.member_id = wm.id
                and mp_deny.permission_code = requested_permission
                and mp_deny.effect = 'deny'
          )
    )
    or exists (
        select 1
        from public.workspace_members wm
        join public.member_permissions mp on mp.member_id = wm.id
        where wm.workspace_id = requested_workspace
          and wm.user_id = requested_user
          and wm.status = 'active'
          and mp.permission_code = requested_permission
          and mp.effect = 'allow'
          and not exists (
              select 1
              from public.member_permissions mp_deny
              where mp_deny.member_id = wm.id
                and mp_deny.permission_code = requested_permission
                and mp_deny.effect = 'deny'
          )
    );
$$;

create table public.financial_accounts (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    name text not null,
    account_type text not null,
    currency char(3) not null default 'PHP',
    starting_balance numeric(20, 2) not null default 0,
    is_active boolean not null default true,
    owner_user_id uuid references public.profiles(id),
    deleted_at timestamptz,
    deleted_by uuid references public.profiles(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.ledger_accounts (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    financial_account_id uuid references public.financial_accounts(id) on delete restrict,
    name text not null,
    account_type public.ledger_account_type not null,
    currency char(3) not null default 'PHP',
    created_at timestamptz not null default now(),
    unique (workspace_id, name)
);

create table public.ledger_transactions (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    source_type text not null,
    source_id uuid not null,
    currency char(3) not null default 'PHP',
    posted_at timestamptz not null default now(),
    created_by uuid not null references public.profiles(id),
    reversal_of uuid references public.ledger_transactions(id),
    created_at timestamptz not null default now(),
    unique (source_type, source_id)
);

create table public.ledger_entries (
    id uuid primary key default gen_random_uuid(),
    ledger_transaction_id uuid not null references public.ledger_transactions(id) on delete restrict,
    ledger_account_id uuid not null references public.ledger_accounts(id) on delete restrict,
    debit numeric(20, 2) not null default 0 check (debit >= 0),
    credit numeric(20, 2) not null default 0 check (credit >= 0),
    created_at timestamptz not null default now(),
    check ((debit > 0 and credit = 0) or (credit > 0 and debit = 0))
);

create table public.debts (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    debtor_profile_id uuid references public.profiles(id),
    creditor_profile_id uuid references public.profiles(id),
    direction text not null check (direction in ('payable', 'receivable')),
    title text not null,
    original_amount numeric(20, 2) not null check (original_amount >= 0),
    interest_amount numeric(20, 2) not null default 0 check (interest_amount >= 0),
    fee_amount numeric(20, 2) not null default 0 check (fee_amount >= 0),
    total_payable numeric(20, 2) generated always as (original_amount + interest_amount + fee_amount) stored,
    currency char(3) not null default 'PHP',
    status public.debt_status not null default 'pending',
    priority text not null default 'normal' check (priority in ('normal', 'starred', 'high')),
    deleted_at timestamptz,
    deleted_by uuid references public.profiles(id),
    created_by uuid not null references public.profiles(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.installment_schedules (
    id uuid primary key default gen_random_uuid(),
    debt_id uuid not null references public.debts(id) on delete cascade,
    installment_number integer not null check (installment_number > 0),
    due_date date not null,
    required_amount numeric(20, 2) not null check (required_amount >= 0),
    principal_amount numeric(20, 2) not null default 0 check (principal_amount >= 0),
    interest_amount numeric(20, 2) not null default 0 check (interest_amount >= 0),
    fee_amount numeric(20, 2) not null default 0 check (fee_amount >= 0),
    status public.payment_status not null default 'upcoming',
    unique (debt_id, installment_number)
);

create table public.payments (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    debt_id uuid not null references public.debts(id) on delete restrict,
    financial_account_id uuid references public.financial_accounts(id) on delete restrict,
    amount numeric(20, 2) not null check (amount > 0),
    currency char(3) not null default 'PHP',
    paid_at timestamptz not null default now(),
    created_by uuid not null references public.profiles(id),
    created_at timestamptz not null default now()
);

create table public.payment_allocations (
    payment_id uuid not null references public.payments(id) on delete restrict,
    installment_id uuid not null references public.installment_schedules(id) on delete restrict,
    principal_amount numeric(20, 2) not null default 0 check (principal_amount >= 0),
    interest_amount numeric(20, 2) not null default 0 check (interest_amount >= 0),
    fee_amount numeric(20, 2) not null default 0 check (fee_amount >= 0),
    primary key (payment_id, installment_id)
);

create table public.audit_logs (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    actor_user_id uuid references public.profiles(id),
    operation_id uuid,
    action text not null,
    record_type text not null,
    record_id uuid,
    previous_value jsonb,
    new_value jsonb,
    server_recorded_at timestamptz not null default now()
);

create table public.sync_operations (
    operation_id uuid primary key,
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    record_type text not null,
    record_id uuid not null,
    operation text not null check (operation in ('create', 'update', 'delete', 'restore', 'reverse')),
    client_sequence bigint,
    observed_version bigint,
    schema_version text not null,
    device_id text not null,
    status public.sync_status not null default 'pending',
    error_message text,
    server_version bigint,
    created_at timestamptz not null default now(),
    processed_at timestamptz
);

create table public.attachments (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.workspaces(id) on delete cascade,
    storage_path text not null,
    file_name text not null,
    file_type text not null,
    related_record_type text not null,
    related_record_id uuid not null,
    uploaded_by uuid not null references public.profiles(id),
    created_at timestamptz not null default now()
);

create or replace function public.create_workspace(
    workspace_name text,
    requested_type public.workspace_type,
    requested_currency char(3) default 'PHP',
    requested_timezone text default 'Asia/Manila'
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    new_workspace_id uuid;
    owner_role_id uuid;
    admin_role_id uuid;
    member_role_id uuid;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    insert into public.profiles (id)
    values (auth.uid())
    on conflict (id) do nothing;

    insert into public.workspaces (name, workspace_type, base_currency, timezone, created_by)
    values (trim(workspace_name), requested_type, upper(requested_currency), requested_timezone, auth.uid())
    returning id into new_workspace_id;

    insert into public.roles (workspace_id, name, is_admin)
    values (new_workspace_id, 'Owner', true)
    returning id into owner_role_id;

    insert into public.roles (workspace_id, name, is_admin)
    values (new_workspace_id, 'Admin', true)
    returning id into admin_role_id;

    insert into public.roles (workspace_id, name, is_admin)
    values (new_workspace_id, 'Member', false)
    returning id into member_role_id;

    insert into public.workspace_members (workspace_id, user_id, role_id)
    values (new_workspace_id, auth.uid(), owner_role_id);

    insert into public.role_permissions (role_id, permission_code)
    select owner_role_id, code from public.permissions;

    insert into public.role_permissions (role_id, permission_code)
    select admin_role_id, code from public.permissions
    where code in (
        'view_workspace',
        'manage_users',
        'view_accounts',
        'manage_accounts',
        'view_ledger',
        'create_transactions',
        'edit_transactions',
        'delete_transactions',
        'view_debts',
        'manage_debts',
        'manage_reports',
        'manage_attachments'
    );

    insert into public.role_permissions (role_id, permission_code)
    select member_role_id, code from public.permissions
    where code in (
        'view_workspace',
        'view_accounts',
        'view_ledger',
        'create_transactions',
        'view_debts',
        'manage_reports'
    );

    return new_workspace_id;
end;
$$;

grant execute on function public.create_workspace(text, public.workspace_type, char(3), text) to authenticated;

alter table public.profiles enable row level security;
alter table public.workspaces enable row level security;
alter table public.roles enable row level security;
alter table public.permissions enable row level security;
alter table public.workspace_members enable row level security;
alter table public.role_permissions enable row level security;
alter table public.member_permissions enable row level security;
alter table public.financial_accounts enable row level security;
alter table public.ledger_accounts enable row level security;
alter table public.ledger_transactions enable row level security;
alter table public.ledger_entries enable row level security;
alter table public.debts enable row level security;
alter table public.installment_schedules enable row level security;
alter table public.payments enable row level security;
alter table public.payment_allocations enable row level security;
alter table public.audit_logs enable row level security;
alter table public.sync_operations enable row level security;
alter table public.attachments enable row level security;

create policy profiles_self_select on public.profiles for select using (id = auth.uid());
create policy workspaces_select on public.workspaces for select using (public.has_workspace_permission(auth.uid(), id, 'view_workspace'));
create policy members_select on public.workspace_members for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_workspace'));
create policy roles_select on public.roles for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_workspace'));
create policy permissions_select on public.permissions for select using (auth.uid() is not null);
create policy accounts_select on public.financial_accounts for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_accounts'));
create policy ledger_accounts_select on public.ledger_accounts for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_ledger'));
create policy ledger_transactions_select on public.ledger_transactions for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_ledger'));
create policy ledger_entries_select on public.ledger_entries for select using (
    exists (
        select 1 from public.ledger_transactions lt
        where lt.id = ledger_transaction_id
          and public.has_workspace_permission(auth.uid(), lt.workspace_id, 'view_ledger')
    )
);
create policy debts_select on public.debts for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_debts'));
create policy schedules_select on public.installment_schedules for select using (
    exists (select 1 from public.debts d where d.id = debt_id and public.has_workspace_permission(auth.uid(), d.workspace_id, 'view_debts'))
);
create policy payments_select on public.payments for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_debts'));
create policy allocations_select on public.payment_allocations for select using (
    exists (select 1 from public.payments p where p.id = payment_id and public.has_workspace_permission(auth.uid(), p.workspace_id, 'view_debts'))
);
create policy audit_logs_select on public.audit_logs for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_workspace'));
create policy sync_operations_select on public.sync_operations for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'view_workspace'));
create policy attachments_select on public.attachments for select using (public.has_workspace_permission(auth.uid(), workspace_id, 'manage_attachments'));

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id) values (new.id)
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute procedure public.handle_new_user();

create index financial_accounts_workspace_idx on public.financial_accounts(workspace_id) where deleted_at is null;
create index ledger_transactions_workspace_posted_idx on public.ledger_transactions(workspace_id, posted_at);
create index debts_workspace_status_idx on public.debts(workspace_id, status) where deleted_at is null;
create index schedules_due_date_idx on public.installment_schedules(due_date, status);
create index audit_logs_workspace_time_idx on public.audit_logs(workspace_id, server_recorded_at desc);
create index sync_operations_status_idx on public.sync_operations(workspace_id, status);
