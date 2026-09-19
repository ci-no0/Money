package com.whereismy.money.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.Permission
import com.whereismy.money.data.Profile
import com.whereismy.money.data.Role
import com.whereismy.money.data.Workspace
import com.whereismy.money.data.WorkspaceMember
import com.whereismy.money.databinding.FragmentSettingsBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        binding.settingsTitle.setText(R.string.action_settings)
        binding.settingsStatus.text = "Loading workspace settings..."
        loadWorkspaceSettings()
        return binding.root
    }

    private fun loadWorkspaceSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: error("Authentication required")

                val workspace = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull() ?: error("No workspace found")

                val members = supabaseClient.from("workspace_members")
                    .select()
                    .decodeList<WorkspaceMember>()
                    .filter { it.workspace_id == workspace.id }

                val roles = supabaseClient.from("roles")
                    .select()
                    .decodeList<Role>()
                    .filter { it.workspace_id == workspace.id }

                val profiles = supabaseClient.from("profiles")
                    .select()
                    .decodeList<Profile>()
                    .associateBy { it.id }

                val permissions = supabaseClient.from("permissions")
                    .select()
                    .decodeList<Permission>()
                    .sortedBy { it.code }

                val currentMember = members.firstOrNull { it.user_id == currentUserId }
                val currentRoleName = roles.firstOrNull { it.id == currentMember?.role_id }?.name ?: "No role assigned"
                val roleCounts = roles.associateWith { role ->
                    members.count { it.role_id == role.id }
                }
                val memberSummary = members.joinToString(separator = "\n") { member ->
                    val profile = profiles[member.user_id]
                    val roleName = roles.firstOrNull { it.id == member.role_id }?.name ?: "No role"
                    val label = profile?.display_name?.takeIf { it.isNotBlank() } ?: member.user_id
                    "- $label — $roleName"
                }

                buildString {
                    appendLine("Workspace: ${workspace.name}")
                    appendLine("Type: ${workspace.workspace_type}")
                    appendLine("Currency: ${workspace.base_currency}")
                    appendLine("Timezone: ${workspace.timezone}")
                    appendLine("Members: ${members.size}")
                    appendLine("Role: $currentRoleName")
                    appendLine("Admin roles: ${roleCounts.filterValues { it > 0 }.count()}")
                    appendLine("Permissions: ${permissions.size} granted/available")
                    appendLine()
                    appendLine("Member roster:")
                    appendLine(memberSummary.ifBlank { "- No members found" })
                    appendLine()
                    appendLine("Permission catalog:")
                    append(permissions.joinToString(separator = "\n") { permission ->
                        "- ${permission.code}"
                    })
                }
            }.onSuccess { details ->
                binding.settingsDetails.text = details
                binding.settingsStatus.text = "Workspace access loaded"
            }.onFailure { error ->
                binding.settingsDetails.text = "Workspace settings unavailable."
                binding.settingsStatus.text = error.message ?: "Could not load workspace settings"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
