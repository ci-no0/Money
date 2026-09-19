-- Run after 202609190003_expenses.sql if PostgREST still reports PGRST202.
-- This refreshes the public schema cache without changing financial data.
notify pgrst, 'reload schema';
