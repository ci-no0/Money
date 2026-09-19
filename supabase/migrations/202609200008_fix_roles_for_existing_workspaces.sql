-- Add the missing default roles to all existing workspaces.
-- This is safe to run on an already-initialized database.

insert into public.roles (workspace_id, name, is_admin)
select w.id, 'Owner', true
from public.workspaces w
left join public.roles r on r.workspace_id = w.id and r.name = 'Owner'
where r.id is null
on conflict (workspace_id, name) do nothing;

insert into public.roles (workspace_id, name, is_admin)
select w.id, 'Admin', true
from public.workspaces w
left join public.roles r on r.workspace_id = w.id and r.name = 'Admin'
where r.id is null
on conflict (workspace_id, name) do nothing;

insert into public.roles (workspace_id, name, is_admin)
select w.id, 'Member', false
from public.workspaces w
left join public.roles r on r.workspace_id = w.id and r.name = 'Member'
where r.id is null
on conflict (workspace_id, name) do nothing;

-- Assign any members with no role to the default "Member" role.
update public.workspace_members wm
set role_id = (
    select r.id
    from public.roles r
    where r.workspace_id = wm.workspace_id
      and r.name = 'Member'
    limit 1
)
where wm.role_id is null;

-- Give the default role permissions for each role type.
insert into public.role_permissions (role_id, permission_code)
select r.id, p.code
from public.roles r
join public.permissions p on true
where r.name = 'Owner'
  and not exists (
      select 1
      from public.role_permissions rp
      where rp.role_id = r.id
        and rp.permission_code = p.code
  )
on conflict (role_id, permission_code) do nothing;

insert into public.role_permissions (role_id, permission_code)
select r.id, p.code
from public.roles r
join public.permissions p on p.code in (
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
)
where r.name = 'Admin'
  and not exists (
      select 1
      from public.role_permissions rp
      where rp.role_id = r.id
        and rp.permission_code = p.code
  )
on conflict (role_id, permission_code) do nothing;

insert into public.role_permissions (role_id, permission_code)
select r.id, p.code
from public.roles r
join public.permissions p on p.code in (
    'view_workspace',
    'view_accounts',
    'view_ledger',
    'create_transactions',
    'view_debts',
    'manage_reports'
)
where r.name = 'Member'
  and not exists (
      select 1
      from public.role_permissions rp
      where rp.role_id = r.id
        and rp.permission_code = p.code
  )
on conflict (role_id, permission_code) do nothing;
