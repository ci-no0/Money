package com.whereismy.money.ui.debts

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.Debt
import com.whereismy.money.data.DebtPayment
import com.whereismy.money.data.FinancialAccount
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentDebtBinding
import com.whereismy.money.supabaseClient
import android.widget.ArrayAdapter
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

class DebtFragment : Fragment() {

    private var _binding: FragmentDebtBinding? = null
    private val binding get() = _binding!!
    private var activeWorkspaceId: String? = null
    private var debts: List<Debt> = emptyList()
    private var accounts: List<FinancialAccount> = emptyList()
    private var payments: List<DebtPayment> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebtBinding.inflate(inflater, container, false)
        binding.createDebtButton.setOnClickListener { createDebt() }
        binding.createPaymentButton.setOnClickListener { createPayment() }
        binding.postReceiptButton.setOnClickListener { postReceipt() }
        binding.reversePaymentButton.setOnClickListener { reversePayment() }
        binding.debtStartDateInput.setText(LocalDate.now().toString())
        binding.debtStartDateInput.setOnClickListener { showDebtStartDatePicker() }
        loadWorkspaceAndDebts()
        return binding.root
    }

    private fun loadWorkspaceAndDebts() {
        binding.debtStatus.setText(R.string.debt_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select().decodeList<Workspace>().firstOrNull()
                    ?: error(getString(R.string.workspace_required))
                supabaseClient.postgrest.rpc(
                    "refresh_debt_payment_statuses",
                    parameters = buildJsonObject { put("requested_workspace", workspace.id) },
                )
                val debts = supabaseClient.from("debts")
                    .select().decodeList<Debt>()
                val accounts = supabaseClient.from("financial_accounts")
                    .select().decodeList<FinancialAccount>()
                    .filter { it.workspace_id == workspace.id && it.is_active }
                val payments = supabaseClient.from("payments")
                    .select().decodeList<DebtPayment>()
                Triple(workspace.id, debts.filterNot { it.status == "cancelled" }, accounts) to payments
            }.onSuccess { (workspaceData, loadedPayments) ->
                val (workspaceId, loadedDebts, loadedAccounts) = workspaceData
                activeWorkspaceId = workspaceId
                debts = loadedDebts
                accounts = loadedAccounts
                payments = loadedPayments
                binding.debtList.text = if (debts.isEmpty()) {
                    getString(R.string.no_debts)
                } else {
                    debts.joinToString("\n") {
                        "${it.title}: ${it.currency} ${it.total_payable.jsonPrimitive.content} (${it.status})"
                    }
                }
                binding.paymentDebtSpinner.adapter = ArrayAdapter(
                    requireContext(), android.R.layout.simple_spinner_dropdown_item,
                    debts.map { "${it.title} (${it.currency} ${it.total_payable.jsonPrimitive.content})" },
                )
                binding.paymentAccountSpinner.adapter = ArrayAdapter(
                    requireContext(), android.R.layout.simple_spinner_dropdown_item,
                    accounts.map { "${it.name} (${it.currency})" },
                )
                binding.paymentHistory.text = if (payments.isEmpty()) {
                    getString(R.string.no_payments)
                } else {
                    payments.joinToString("\n") { payment ->
                        val debtTitle = debts.firstOrNull { it.id == payment.debt_id }?.title ?: payment.debt_id
                        val state = if (payment.reversed_at == null) "posted" else "reversed"
                        "$debtTitle: ${payment.currency} ${payment.amount.jsonPrimitive.content} ($state)"
                    }
                }
                binding.paymentHistorySpinner.adapter = ArrayAdapter(
                    requireContext(), android.R.layout.simple_spinner_dropdown_item,
                    payments.map { payment ->
                        val debtTitle = debts.firstOrNull { it.id == payment.debt_id }?.title ?: payment.debt_id
                        "$debtTitle: ${payment.currency} ${payment.amount.jsonPrimitive.content}"
                    },
                )
                binding.createPaymentButton.isEnabled = debts.isNotEmpty() && accounts.isNotEmpty()
                binding.postReceiptButton.isEnabled = debts.isNotEmpty() && accounts.isNotEmpty()
                binding.reversePaymentButton.isEnabled = payments.any { it.reversed_at == null }
                binding.debtStatus.setText(R.string.debt_ready)
            }.onFailure { showError(it) }
        }
    }

    private fun reversePayment() {
        val workspaceId = activeWorkspaceId
        val payment = payments.getOrNull(binding.paymentHistorySpinner.selectedItemPosition)
        if (workspaceId == null || payment == null) {
            binding.debtStatus.setText(R.string.no_payment_to_reverse)
            return
        }
        if (payment.reversed_at != null) {
            binding.debtStatus.setText(R.string.payment_already_reversed)
            return
        }

        binding.reversePaymentButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "reverse_debt_payment",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_payment", payment.id)
                    },
                )
            }.onSuccess {
                binding.debtStatus.setText(R.string.payment_reversed)
                loadWorkspaceAndDebts()
            }.onFailure { showError(it) }
            binding.reversePaymentButton.isEnabled = payments.any { it.reversed_at == null }
        }
    }

    private fun postReceipt() {
        val workspaceId = activeWorkspaceId
        val debt = debts.getOrNull(binding.paymentDebtSpinner.selectedItemPosition)
        val account = accounts.getOrNull(binding.paymentAccountSpinner.selectedItemPosition)
        if (workspaceId == null || debt == null || account == null) {
            binding.debtStatus.setText(R.string.payment_requires_debt_account)
            return
        }

        binding.postReceiptButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "post_debt_receipt",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_debt", debt.id)
                        put("requested_account", account.id)
                    },
                )
            }.onSuccess {
                binding.debtStatus.setText(R.string.receipt_created)
            }.onFailure { showError(it) }
            binding.postReceiptButton.isEnabled = debts.isNotEmpty() && accounts.isNotEmpty()
        }
    }

    private fun createPayment() {
        val workspaceId = activeWorkspaceId
        val debt = debts.getOrNull(binding.paymentDebtSpinner.selectedItemPosition)
        val account = accounts.getOrNull(binding.paymentAccountSpinner.selectedItemPosition)
        val amount = parseAmount(binding.paymentAmountInput.text.toString())

        if (workspaceId == null || debt == null || account == null) {
            binding.debtStatus.setText(R.string.payment_requires_debt_account)
            return
        }
        if (amount == null || amount.signum() <= 0) {
            binding.debtStatus.setText(R.string.invalid_payment_amount)
            return
        }

        binding.createPaymentButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "create_debt_payment",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_debt", debt.id)
                        put("requested_account", account.id)
                        put("requested_amount", amount.toPlainString())
                    },
                )
            }.onSuccess {
                binding.paymentAmountInput.text.clear()
                binding.debtStatus.setText(R.string.payment_created)
                loadWorkspaceAndDebts()
            }.onFailure { showError(it) }
            binding.createPaymentButton.isEnabled = debts.isNotEmpty() && accounts.isNotEmpty()
        }
    }

    private fun showDebtStartDatePicker() {
        val selectedDate = binding.debtStartDateInput.text.toString().trim().ifBlank { LocalDate.now().toString() }
        val parsed = runCatching { LocalDate.parse(selectedDate) }.getOrDefault(LocalDate.now())
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val picked = LocalDate.of(year, month + 1, dayOfMonth)
                binding.debtStartDateInput.setText(picked.toString())
            },
            parsed.year,
            parsed.monthValue - 1,
            parsed.dayOfMonth,
        ).show()
    }

    private fun createDebt() {
        val workspaceId = activeWorkspaceId
        val title = binding.debtTitleInput.text.toString().trim()
        val principal = parseAmount(binding.debtPrincipalInput.text.toString())
        val interest = parseAmount(binding.debtInterestInput.text.toString()) ?: BigDecimal.ZERO
        val fee = parseAmount(binding.debtFeeInput.text.toString()) ?: BigDecimal.ZERO
        val months = binding.debtMonthsInput.text.toString().trim().toIntOrNull()
        val startDate = binding.debtStartDateInput.text.toString().trim().ifBlank { LocalDate.now().toString() }

        if (workspaceId == null || title.isBlank()) {
            binding.debtStatus.setText(R.string.debt_title_required)
            return
        }
        if (principal == null || principal.signum() <= 0 || interest.signum() < 0 || fee.signum() < 0) {
            binding.debtStatus.setText(R.string.invalid_debt_amount)
            return
        }
        if (months == null || months !in 1..600) {
            binding.debtStatus.setText(R.string.invalid_debt_months)
            return
        }

        binding.createDebtButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "create_debt",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_title", title)
                        put("requested_direction", binding.debtDirectionSpinner.selectedItem.toString().lowercase())
                        put("requested_principal", principal.toPlainString())
                        put("requested_interest", interest.toPlainString())
                        put("requested_fee", fee.toPlainString())
                        put("requested_months", months)
                        put("requested_start_date", startDate)
                    },
                )
            }.onSuccess {
                binding.debtTitleInput.text.clear()
                binding.debtPrincipalInput.text.clear()
                binding.debtInterestInput.text.clear()
                binding.debtFeeInput.text.clear()
                binding.debtMonthsInput.text.clear()
                binding.debtStatus.setText(R.string.debt_created)
                loadWorkspaceAndDebts()
            }.onFailure { showError(it) }
            binding.createDebtButton.isEnabled = true
        }
    }

    private fun parseAmount(value: String): BigDecimal? =
        value.trim().ifBlank { "0" }.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun showError(error: Throwable) {
        binding.debtStatus.text = error.message ?: getString(R.string.debt_error)
        binding.createDebtButton.isEnabled = true
        binding.createPaymentButton.isEnabled = debts.isNotEmpty() && accounts.isNotEmpty()
        binding.postReceiptButton.isEnabled = debts.isNotEmpty() && accounts.isNotEmpty()
        binding.reversePaymentButton.isEnabled = payments.any { it.reversed_at == null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
