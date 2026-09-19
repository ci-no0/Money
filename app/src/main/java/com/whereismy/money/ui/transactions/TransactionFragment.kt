package com.whereismy.money.ui.transactions

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.FinancialAccount
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentTransactionBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

class TransactionFragment : Fragment() {

    private var _binding: FragmentTransactionBinding? = null
    private val binding get() = _binding!!
    private var activeWorkspaceId: String? = null
    private var accounts: List<FinancialAccount> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionBinding.inflate(inflater, container, false)
        binding.createExpenseButton.setOnClickListener { createExpense() }
        binding.expenseDateInput.setText(LocalDate.now().toString())
        binding.expenseDateInput.setOnClickListener { showExpenseDatePicker() }
        loadWorkspaceAndAccounts()
        return binding.root
    }

    private fun loadWorkspaceAndAccounts() {
        binding.transactionStatus.setText(R.string.transaction_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull()
                    ?: error(getString(R.string.workspace_required))
                val availableAccounts = supabaseClient.from("financial_accounts")
                    .select()
                    .decodeList<FinancialAccount>()
                    .filter { it.workspace_id == workspace.id && it.is_active }
                workspace to availableAccounts
            }.onSuccess { (workspace, availableAccounts) ->
                activeWorkspaceId = workspace.id
                accounts = availableAccounts
                binding.accountSpinner.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    accounts.map { "${it.name} (${it.currency})" },
                )
                binding.transactionStatus.setText(
                    if (accounts.isEmpty()) R.string.no_accounts_for_transaction
                    else R.string.transaction_ready
                )
                binding.createExpenseButton.isEnabled = accounts.isNotEmpty()
            }.onFailure {
                showError(it)
            }
        }
    }

    private fun showExpenseDatePicker() {
        val selectedDate = binding.expenseDateInput.text.toString().trim().ifBlank { LocalDate.now().toString() }
        val parsed = runCatching { LocalDate.parse(selectedDate) }.getOrDefault(LocalDate.now())
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val picked = LocalDate.of(year, month + 1, dayOfMonth)
                binding.expenseDateInput.setText(picked.toString())
            },
            parsed.year,
            parsed.monthValue - 1,
            parsed.dayOfMonth,
        ).show()
    }

    private fun createExpense() {
        val workspaceId = activeWorkspaceId
        val selectedAccount = accounts.getOrNull(binding.accountSpinner.selectedItemPosition)
        val category = binding.expenseCategoryInput.text.toString().trim()
        val amountText = binding.expenseAmountInput.text.toString().trim()
        val amount = runCatching { BigDecimal(amountText) }.getOrNull()
        val note = binding.expenseNoteInput.text.toString().trim()
        val transactionDate = binding.expenseDateInput.text.toString().trim().ifBlank { LocalDate.now().toString() }

        if (workspaceId == null || selectedAccount == null) {
            binding.transactionStatus.setText(R.string.no_accounts_for_transaction)
            return
        }
        if (category.isBlank()) {
            binding.transactionStatus.setText(R.string.expense_category_required)
            return
        }
        if (amount == null || amount.signum() <= 0) {
            binding.transactionStatus.setText(R.string.invalid_expense_amount)
            return
        }

        binding.createExpenseButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "create_expense",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_account", selectedAccount.id)
                        put("requested_category", category)
                        put("requested_amount", amount.toPlainString())
                        put("requested_note", note.ifBlank { null })
                        put("requested_transaction_date", transactionDate)
                    },
                )
            }.onSuccess {
                binding.expenseCategoryInput.text.clear()
                binding.expenseAmountInput.text.clear()
                binding.expenseNoteInput.text.clear()
                binding.transactionStatus.setText(R.string.expense_created)
            }.onFailure {
                showError(it)
            }
            binding.createExpenseButton.isEnabled = accounts.isNotEmpty()
        }
    }

    private fun showError(error: Throwable) {
        val message = error.message.orEmpty()
        binding.transactionStatus.setText(
            if (message.contains("PGRST202") || message.contains("schema cache")) {
                R.string.expense_migration_required
            } else {
                R.string.transaction_error
            }
        )
        binding.createExpenseButton.isEnabled = accounts.isNotEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
