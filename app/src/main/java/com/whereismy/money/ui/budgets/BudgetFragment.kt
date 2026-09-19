package com.whereismy.money.ui.budgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.app.DatePickerDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.Budget
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentBudgetBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

class BudgetFragment : Fragment() {

    private var _binding: FragmentBudgetBinding? = null
    private val binding get() = _binding!!
    private var activeWorkspaceId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetBinding.inflate(inflater, container, false)
        binding.createBudgetButton.setOnClickListener { createBudget() }
        binding.budgetMonthInput.setText(LocalDate.now().toString())
        binding.budgetMonthInput.setOnClickListener { showBudgetDatePicker() }
        loadWorkspaceAndBudgets()
        return binding.root
    }

    private fun loadWorkspaceAndBudgets() {
        binding.budgetStatus.text = "Loading budgets..."
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull() ?: error(getString(R.string.workspace_required))
                activeWorkspaceId = workspace.id
                val budgets = supabaseClient.from("budgets")
                    .select()
                    .decodeList<Budget>()
                    .filter { it.workspace_id == workspace.id }
                val overview = supabaseClient.postgrest.rpc(
                    "get_workspace_budget_overview",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspace.id)
                    },
                )
                workspace to (budgets to overview)
            }.onSuccess { (workspace, data) ->
                val (budgets, overview) = data
                binding.budgetList.text = if (budgets.isEmpty()) {
                    getString(R.string.no_budgets)
                } else {
                    budgets.joinToString(separator = "\n") { budget ->
                        "${budget.name}: ${budget.category} = ${budget.amount.jsonPrimitive.content}"
                    }
                }

                val rows = when (overview) {
                    is List<*> -> overview
                    is Map<*, *> -> listOf(overview)
                    else -> emptyList<Any?>()
                }
                binding.budgetSummary.text = if (rows.isEmpty()) {
                    "Workspace: ${workspace.name}\nNo budget rows loaded yet."
                } else {
                    rows.joinToString(separator = "\n") { row ->
                        val map = row as? Map<*, *> ?: return@joinToString "Budget row unavailable"
                        val category = map["category"] ?: "Budget"
                        val limit = map["budget_amount"] ?: "0"
                        val spent = map["spent_amount"] ?: "0"
                        val remaining = map["remaining_amount"] ?: "0"
                        "$category | limit=$limit | spent=$spent | remaining=$remaining"
                    }
                }
                binding.budgetStatus.text = getString(R.string.budget_ready)
            }.onFailure {
                showError(it)
            }
        }
    }

    private fun showBudgetDatePicker() {
        val selectedDate = binding.budgetMonthInput.text.toString().trim().ifBlank { LocalDate.now().toString() }
        val parsed = runCatching { LocalDate.parse(selectedDate) }.getOrDefault(LocalDate.now())
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val picked = LocalDate.of(year, month + 1, dayOfMonth)
                binding.budgetMonthInput.setText(picked.toString())
            },
            parsed.year,
            parsed.monthValue - 1,
            parsed.dayOfMonth,
        ).show()
    }

    private fun createBudget() {
        val workspaceId = activeWorkspaceId
        val name = binding.budgetNameInput.text.toString().trim()
        val category = binding.budgetCategoryInput.text.toString().trim()
        val amountText = binding.budgetAmountInput.text.toString().trim()
        val amount = runCatching { BigDecimal(amountText) }.getOrNull()
        val month = binding.budgetMonthInput.text.toString().trim().ifBlank { LocalDate.now().toString() }

        if (workspaceId == null) {
            binding.budgetStatus.text = getString(R.string.workspace_required)
            return
        }
        if (name.isBlank()) {
            binding.budgetStatus.text = getString(R.string.budget_name_required)
            return
        }
        if (category.isBlank()) {
            binding.budgetStatus.text = getString(R.string.budget_category_required)
            return
        }
        if (amount == null || amount.signum() <= 0) {
            binding.budgetStatus.text = getString(R.string.invalid_budget_amount)
            return
        }

        binding.createBudgetButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "create_budget",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_name", name)
                        put("requested_category", category)
                        put("requested_amount", amount.toPlainString())
                        put("requested_budget_month", month.ifBlank { LocalDate.now().toString() })
                    },
                )
            }.onSuccess {
                binding.budgetNameInput.text.clear()
                binding.budgetCategoryInput.text.clear()
                binding.budgetAmountInput.text.clear()
                binding.budgetStatus.text = getString(R.string.budget_created)
                loadWorkspaceAndBudgets()
            }.onFailure { showError(it) }
            binding.createBudgetButton.isEnabled = true
        }
    }

    private fun showError(error: Throwable) {
        binding.budgetStatus.text = error.message ?: getString(R.string.budget_error)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
