-- Debt records and exact installment schedule generation.
-- Run after 202609190003_expenses.sql.

create or replace function public.create_debt(
    requested_workspace uuid,
    requested_title text,
    requested_direction text,
    requested_principal numeric(20, 2),
    requested_interest numeric(20, 2) default 0,
    requested_fee numeric(20, 2) default 0,
    requested_months integer default 1,
    requested_start_date date default current_date
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    new_debt_id uuid;
    total_payable numeric(20, 2);
    base_payment numeric(20, 2);
    installment_amount numeric(20, 2);
    installment_index integer;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if not public.has_workspace_permission(auth.uid(), requested_workspace, 'manage_debts') then
        raise exception 'You do not have permission to manage debts in this workspace';
    end if;

    if trim(coalesce(requested_title, '')) = '' then
        raise exception 'Debt title is required';
    end if;
    if requested_direction not in ('payable', 'receivable') then
        raise exception 'Debt direction must be payable or receivable';
    end if;
    if requested_principal is null or requested_principal <= 0 then
        raise exception 'Principal must be greater than zero';
    end if;
    if coalesce(requested_interest, 0) < 0 or coalesce(requested_fee, 0) < 0 then
        raise exception 'Interest and fees cannot be negative';
    end if;
    if requested_months is null or requested_months < 1 or requested_months > 600 then
        raise exception 'Installment months must be between 1 and 600';
    end if;

    total_payable := requested_principal + coalesce(requested_interest, 0) + coalesce(requested_fee, 0);
    base_payment := round(total_payable / requested_months, 2);

    insert into public.debts (
        workspace_id,
        direction,
        title,
        original_amount,
        interest_amount,
        fee_amount,
        currency,
        status,
        created_by
    ) values (
        requested_workspace,
        requested_direction,
        trim(requested_title),
        requested_principal,
        coalesce(requested_interest, 0),
        coalesce(requested_fee, 0),
        'PHP',
        'active',
        auth.uid()
    ) returning id into new_debt_id;

    for installment_index in 1..requested_months loop
        if installment_index = requested_months then
            installment_amount := total_payable - (base_payment * (requested_months - 1));
        else
            installment_amount := base_payment;
        end if;

        insert into public.installment_schedules (
            debt_id,
            installment_number,
            due_date,
            required_amount,
            principal_amount,
            interest_amount,
            fee_amount
        ) values (
            new_debt_id,
            installment_index,
            (requested_start_date + ((installment_index - 1) * interval '1 month'))::date,
            installment_amount,
            case when total_payable = 0 then 0 else round(requested_principal * installment_amount / total_payable, 2) end,
            case when total_payable = 0 then 0 else round(coalesce(requested_interest, 0) * installment_amount / total_payable, 2) end,
            case when total_payable = 0 then 0 else installment_amount
                - round(requested_principal * installment_amount / total_payable, 2)
                - round(coalesce(requested_interest, 0) * installment_amount / total_payable, 2) end
        );
    end loop;

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
        'debt',
        new_debt_id,
        jsonb_build_object(
            'title', trim(requested_title),
            'direction', requested_direction,
            'total_payable', total_payable,
            'installments', requested_months
        )
    );

    return new_debt_id;
end;
$$;

grant execute on function public.create_debt(uuid, text, text, numeric, numeric, numeric, integer, date) to authenticated;

drop policy if exists debts_insert on public.debts;
create policy debts_insert on public.debts for insert
    with check (public.has_workspace_permission(auth.uid(), workspace_id, 'manage_debts'));

drop policy if exists schedules_insert on public.installment_schedules;
create policy schedules_insert on public.installment_schedules for insert
    with check (exists (
        select 1 from public.debts d
        where d.id = debt_id
          and public.has_workspace_permission(auth.uid(), d.workspace_id, 'manage_debts')
    ));

notify pgrst, 'reload schema';
