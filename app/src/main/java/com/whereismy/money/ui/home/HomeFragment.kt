package com.whereismy.money.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.dashboard.DashboardSummaryCalculator
import com.whereismy.money.data.DashboardSummary
import com.whereismy.money.data.Debt
import com.whereismy.money.data.FinancialAccount
import com.whereismy.money.data.LedgerAccount
import com.whereismy.money.data.LedgerEntry
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentHomeBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal

class HomeFragment : Fragment() {

    private data class DashboardTotals(
        val totalCash: BigDecimal,
        val totalDebts: BigDecimal,
        val totalExpenses: BigDecimal,
        val netWorth: BigDecimal,
        val cashFlow: BigDecimal,
    )

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private var isSignUpMode = false
    private var activeWorkspaceId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        binding.authButton.setOnClickListener { authenticate() }
        binding.toggleAuthButton.setOnClickListener {
            isSignUpMode = !isSignUpMode
            updateAuthMode()
        }
        binding.signOutButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { supabaseClient.auth.signOut() }
                    .onSuccess { showSignedOutState() }
                    .onFailure { showError(it) }
            }
        }
        binding.createWorkspaceButton.setOnClickListener { createWorkspace() }
        binding.createAccountButton.setOnClickListener { createFinancialAccount() }
        showCurrentSession()
        return binding.root
    }

    private fun showCurrentSession() {
        if (supabaseClient.auth.currentUserOrNull() == null) {
            showSignedOutState()
        } else {
            showSignedInState()
        }
    }

    private fun authenticate() {
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            binding.statusHome.setText(R.string.missing_credentials)
            return
        }

        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (isSignUpMode) {
                    supabaseClient.auth.signUpWith(Email) {
                        this.email = email
                        this.password = password
                    }
                } else {
                    supabaseClient.auth.signInWith(Email) {
                        this.email = email
                        this.password = password
                    }
                }
            }.onSuccess {
                if (supabaseClient.auth.currentUserOrNull() == null) {
                    binding.statusHome.setText(R.string.check_email)
                } else {
                    showSignedInState()
                }
            }.onFailure {
                showError(it)
            }
            setLoading(false)
        }
    }

    private fun updateAuthMode() {
        binding.authButton.setText(if (isSignUpMode) R.string.sign_up else R.string.sign_in)
        binding.toggleAuthButton.setText(
            if (isSignUpMode) R.string.switch_to_sign_in else R.string.create_account
        )
    }

    private fun showSignedInState() {
        binding.statusHome.setText(R.string.signed_in)
        binding.emailInput.visibility = View.GONE
        binding.passwordInput.visibility = View.GONE
        binding.authButton.visibility = View.GONE
        binding.toggleAuthButton.visibility = View.GONE
        binding.signOutButton.visibility = View.VISIBLE
        binding.workspaceSection.visibility = View.VISIBLE
        binding.accountSection.visibility = View.GONE
        loadWorkspaces()
    }

    private fun showSignedOutState() {
        binding.statusHome.setText(R.string.auth_required)
        binding.emailInput.visibility = View.VISIBLE
        binding.passwordInput.visibility = View.VISIBLE
        binding.authButton.visibility = View.VISIBLE
        binding.toggleAuthButton.visibility = View.VISIBLE
        binding.signOutButton.visibility = View.GONE
        binding.workspaceSection.visibility = View.GONE
        binding.accountSection.visibility = View.GONE
        updateAuthMode()
    }

    private fun loadWorkspaces() {
        binding.workspaceList.setText(R.string.workspace_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
            }.onSuccess { workspaces ->
                activeWorkspaceId = workspaces.firstOrNull()?.id
                binding.workspaceList.text = if (workspaces.isEmpty()) {
                    getString(R.string.no_workspaces)
                } else {
                    workspaces.joinToString(separator = "\n") { workspace ->
                        "${workspace.name} (${workspace.workspace_type})"
                    }
                }
                if (activeWorkspaceId == null) {
                    binding.accountSection.visibility = View.GONE
                } else {
                    binding.accountSection.visibility = View.VISIBLE
                    loadFinancialAccounts()
                }
            }.onFailure {
                showError(it)
            }
        }
    }

    private fun loadFinancialAccounts() {
        binding.accountList.setText(R.string.account_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspaceId = activeWorkspaceId
                    ?: error(getString(R.string.workspace_required))
                val summary = supabaseClient.postgrest.rpc(
                    "get_workspace_dashboard",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                    },
                )
                val summaryRows = summary as? List<*> ?: error("Dashboard summary is unavailable")
                val first = summaryRows.firstOrNull() as? Map<*, *> ?: error("Dashboard summary is empty")
                val currency = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull { it.id == workspaceId }
                    ?.base_currency
                    ?: "PHP"
                val accounts = supabaseClient.from("financial_accounts")
                    .select()
                    .decodeList<FinancialAccount>()
                    .filter { it.workspace_id == workspaceId && it.is_active }
                val totalCash = BigDecimal(first["total_cash"].toString())
                val totalDebts = BigDecimal(first["total_debts"].toString())
                val totalExpenses = BigDecimal(first["total_expenses"].toString())
                val netWorth = BigDecimal(first["net_worth"].toString())
                val cashFlow = BigDecimal(first["cash_flow"].toString())
                Triple(accounts, currency, DashboardTotals(totalCash, totalDebts, totalExpenses, netWorth, cashFlow))
            }.onSuccess { (accounts, currency, totals) ->
                binding.dashboardTotalCashValue.text = "$currency ${totals.totalCash}"
                binding.dashboardTotalDebtsValue.text = "$currency ${totals.totalDebts}"
                binding.dashboardNetWorthValue.text = "$currency ${totals.netWorth}"
                binding.dashboardTotalExpensesValue.text = "$currency ${totals.totalExpenses}"
                binding.dashboardCashFlowValue.text = "$currency ${totals.cashFlow}"

                binding.accountList.text = if (accounts.isEmpty()) {
                    getString(R.string.no_accounts)
                } else {
                    accounts.joinToString(separator = "\n") { account ->
                        "${account.name}: ${account.currency} ${account.starting_balance.jsonPrimitive.content}"
                    }
                }
            }.onFailure {
                showError(it)
            }
        }
    }

    private fun createFinancialAccount() {
        val workspaceId = activeWorkspaceId
        val name = binding.accountNameInput.text.toString().trim()
        val balanceText = binding.accountBalanceInput.text.toString().trim().ifBlank { "0" }
        val balance = runCatching { BigDecimal(balanceText) }.getOrNull()
        if (workspaceId == null || name.isBlank()) {
            binding.statusHome.setText(R.string.account_required)
            return
        }
        if (balance == null || balance.signum() < 0) {
            binding.statusHome.setText(R.string.invalid_balance)
            return
        }

        binding.createAccountButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "create_financial_account",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("account_name", name)
                        put("requested_account_type", binding.accountTypeSpinner.selectedItem.toString())
                        put("requested_currency", "PHP")
                        put("requested_starting_balance", balance.toPlainString())
                    },
                )
            }.onSuccess {
                binding.accountNameInput.text.clear()
                binding.accountBalanceInput.text.clear()
                binding.statusHome.setText(R.string.account_created)
                loadFinancialAccounts()
            }.onFailure {
                showError(it)
            }
            binding.createAccountButton.isEnabled = true
        }
    }

    private fun createWorkspace() {
        val name = binding.workspaceNameInput.text.toString().trim()
        if (name.isBlank()) {
            binding.statusHome.setText(R.string.workspace_required)
            return
        }

        binding.createWorkspaceButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val type = binding.workspaceTypeSpinner.selectedItem
                    .toString()
                    .lowercase()
                supabaseClient.postgrest.rpc(
                    "create_workspace",
                    parameters = buildJsonObject {
                        put("workspace_name", name)
                        put("requested_type", type)
                        put("requested_currency", "PHP")
                        put("requested_timezone", "Asia/Manila")
                    },
                )
            }.onSuccess {
                binding.workspaceNameInput.text.clear()
                binding.statusHome.setText(R.string.workspace_created)
                loadWorkspaces()
            }.onFailure {
                showError(it)
            }
            binding.createWorkspaceButton.isEnabled = true
        }
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            binding.statusHome.setText(R.string.auth_loading)
        }
        binding.authButton.isEnabled = !loading
        binding.toggleAuthButton.isEnabled = !loading
    }

    private fun showError(error: Throwable) {
        binding.statusHome.text = error.message ?: getString(R.string.auth_required)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}