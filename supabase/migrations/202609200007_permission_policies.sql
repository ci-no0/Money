-- Allow workspace admins to manage role and member permission mappings.
-- This fixes INSERT/UPDATE/DELETE failures such as:
-- "new row violates row-level security policy for table \"role_permissions\""

drop policy if exists role_permissions_select on public.role_permissions;
drop policy if exists role_permissions_insert on public.role_permissions;
drop policy if exists role_permissions_update on public.role_permissions;
drop policy if exists role_permissions_delete on public.role_permissions;
drop policy if exists member_permissions_select on public.member_permissions;
drop policy if exists member_permissions_insert on public.member_permissions;
drop policy if exists member_permissions_update on public.member_permissions;
drop policy if exists member_permissions_delete on public.member_permissions;

create policy role_permissions_select on public.role_permissions
    for select using (
        exists (
            select 1
            from public.roles r
            where r.id = role_permissions.role_id
              and public.has_workspace_permission(auth.uid(), r.workspace_id, 'view_workspace')
        )
    );

create policy role_permissions_insert on public.role_permissions
    for insert with check (
        exists (
            select 1
            from public.roles r
            where r.id = role_permissions.role_id
              and public.has_workspace_permission(auth.uid(), r.workspace_id, 'manage_users')
        )
    );

create policy role_permissions_update on public.role_permissions
    for update using (
        exists (
            select 1
            from public.roles r
            where r.id = role_permissions.role_id
              and public.has_workspace_permission(auth.uid(), r.workspace_id, 'manage_users')
        )
    )
    with check (
        exists (
            select 1
            from public.roles r
            where r.id = role_permissions.role_id
              and public.has_workspace_permission(auth.uid(), r.workspace_id, 'manage_users')
        )
    );

create policy role_permissions_delete on public.role_permissions
    for delete using (
        exists (
            select 1
            from public.roles r
            where r.id = role_permissions.role_id
              and public.has_workspace_permission(auth.uid(), r.workspace_id, 'manage_users')
        )
    );

create policy member_permissions_select on public.member_permissions
    for select using (
        exists (
            select 1
            from public.workspace_members wm
            where wm.id = member_permissions.member_id
              and public.has_workspace_permission(auth.uid(), wm.workspace_id, 'view_workspace')
        )
    );

create policy member_permissions_insert on public.member_permissions
    for insert with check (
        exists (
            select 1
            from public.workspace_members wm
            where wm.id = member_permissions.member_id
              and public.has_workspace_permission(auth.uid(), wm.workspace_id, 'manage_users')
        )
    );

create policy member_permissions_update on public.member_permissions
    for update using (
        exists (
            select 1
            from public.workspace_members wm
            where wm.id = member_permissions.member_id
              and public.has_workspace_permission(auth.uid(), wm.workspace_id, 'manage_users')
        )
    )
    with check (
        exists (
            select 1
            from public.workspace_members wm
            where wm.id = member_permissions.member_id
              and public.has_workspace_permission(auth.uid(), wm.workspace_id, 'manage_users')
        )
    );

create policy member_permissions_delete on public.member_permissions
    for delete using (
        exists (
            select 1
            from public.workspace_members wm
            where wm.id = member_permissions.member_id
              and public.has_workspace_permission(auth.uid(), wm.workspace_id, 'manage_users')
        )
    );
