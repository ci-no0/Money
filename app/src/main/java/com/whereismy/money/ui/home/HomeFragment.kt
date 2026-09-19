package com.whereismy.money.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.Workspace
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

import com.whereismy.money.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private var isSignUpMode = false

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
                binding.workspaceList.text = if (workspaces.isEmpty()) {
                    getString(R.string.no_workspaces)
                } else {
                    workspaces.joinToString(separator = "\n") { workspace ->
                        "${workspace.name} (${workspace.workspace_type})"
                    }
                }
            }.onFailure {
                showError(it)
            }
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