package com.whereismy.money.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
    private var workspaceRoles: List<Role> = emptyList()
    private var workspaceMembers: List<WorkspaceMember> = emptyList()
    private var allWorkspaces: List<Workspace> = emptyList()
    private var availablePermissions: List<Permission> = emptyList()
    private var permissionByRole: Map<String, List<String>> = emptyMap()
    private var currentUserPermissionCodes: Set<String> = emptySet()
    private val permissionCheckboxes = mutableListOf<android.widget.CheckBox>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        binding.settingsTitle.setText(R.string.action_settings)
        binding.settingsStatus.text = "Loading workspace settings..."
        binding.settingsRefreshButton.setOnClickListener { loadWorkspaceSettings() }
        binding.settingsAssignRoleButton.setOnClickListener { assignSelectedRole() }
        binding.settingsSavePermissionsButton.setOnClickListener { saveSelectedRolePermissions() }
        binding.settingsWorkspaceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadWorkspaceSettings()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.settingsPermissionRoleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePermissionSelectionForSelectedRole()
                updatePermissionSummaryForSelectedRole()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        loadWorkspaceSettings()
        return binding.root
    }

    private fun loadWorkspaceSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
                    ?: error("Authentication required")

                val workspaces = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                allWorkspaces = workspaces
                if (workspaces.isEmpty()) {
                    error("No workspace found")
                }

                val selectedWorkspaceIndex = binding.settingsWorkspaceSpinner.selectedItemPosition.coerceAtLeast(0)
                val workspace = workspaces.getOrNull(selectedWorkspaceIndex) ?: workspaces.first()

                binding.settingsWorkspaceSpinner.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    workspaces.map { it.name },
                )
                val selectedWorkspacePosition = workspaces.indexOfFirst { it.id == workspace.id }
                if (selectedWorkspacePosition >= 0) {
                    binding.settingsWorkspaceSpinner.setSelection(selectedWorkspacePosition)
                }

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
                availablePermissions = permissions

                val rolePermissions = supabaseClient.from("role_permissions")
                    .select()
                    .decodeList<Map<String, String>>()
                permissionByRole = rolePermissions
                    .filter { it["role_id"] in roles.map { role -> role.id } }
                    .groupBy { it["role_id"] ?: "" }
                    .mapValues { (_, entries) -> entries.mapNotNull { it["permission_code"] } }

                val currentMember = members.firstOrNull { it.user_id == currentUserId }
                val currentRoleName = roles.firstOrNull { it.id == currentMember?.role_id }?.name ?: "No role assigned"
                val memberPermissionRows = if (currentMember != null) {
                    supabaseClient.from("member_permissions")
                        .select()
                        .decodeList<Map<String, String>>()
                        .filter { it["member_id"] == currentMember.id }
                } else {
                    emptyList()
                }
                val directPermissions = memberPermissionRows
                    .filter { it["effect"] == "allow" }
                    .mapNotNull { it["permission_code"] }
                    .toSet()
                val deniedPermissions = memberPermissionRows
                    .filter { it["effect"] == "deny" }
                    .mapNotNull { it["permission_code"] }
                    .toSet()
                val currentRolePermissions = if (currentMember?.role_id != null) {
                    permissionByRole[currentMember.role_id].orEmpty().toSet()
                } else emptySet()
                currentUserPermissionCodes = (directPermissions + currentRolePermissions) - deniedPermissions
                val canManageUsers = currentUserPermissionCodes.contains("manage_users")
                binding.settingsAssignRoleButton.isEnabled = canManageUsers
                binding.settingsSavePermissionsButton.isEnabled = canManageUsers
                binding.settingsPermissionRoleSpinner.isEnabled = canManageUsers
                binding.settingsPermissionCheckboxContainer.isEnabled = canManageUsers
                val roleCounts = roles.associateWith { role ->
                    members.count { it.role_id == role.id }
                }
                val memberSummary = members.joinToString(separator = "\n") { member ->
                    val profile = profiles[member.user_id]
                    val roleName = roles.firstOrNull { it.id == member.role_id }?.name ?: "No role"
                    val label = profile?.display_name?.takeIf { it.isNotBlank() } ?: member.user_id
                    "- $label — $roleName"
                }

                workspaceRoles = roles
                workspaceMembers = members

                val memberNames = members.map { member ->
                    val profile = profiles[member.user_id]
                    profile?.display_name?.takeIf { it.isNotBlank() } ?: member.user_id
                }

                binding.settingsMemberSpinner.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    memberNames,
                )
                binding.settingsRoleSpinner.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    roles.map { it.name },
                )
                binding.settingsPermissionRoleSpinner.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    roles.map { it.name },
                )

                updatePermissionSelectionForSelectedRole()
                updatePermissionSummaryForSelectedRole()

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
                binding.settingsStatus.text = if (currentUserPermissionCodes.contains("manage_users")) {
                    "Workspace access loaded"
                } else {
                    "Workspace access loaded — limited to read-only access"
                }
            }.onFailure { error ->
                binding.settingsDetails.text = "Workspace settings unavailable."
                binding.settingsStatus.text = error.message ?: "Could not load workspace settings"
            }
        }
    }

    private fun updatePermissionSelectionForSelectedRole() {
        binding.settingsPermissionCheckboxContainer.removeAllViews()
        permissionCheckboxes.clear()

        if (workspaceRoles.isEmpty()) {
            binding.settingsPermissionSummary.text = "No roles available"
            return
        }

        val selectedRoleIndex = binding.settingsPermissionRoleSpinner.selectedItemPosition.coerceIn(0, workspaceRoles.lastIndex)
        val selectedRole = workspaceRoles[selectedRoleIndex]
        val assignedPermissions = permissionByRole[selectedRole.id].orEmpty().toSet()

        availablePermissions.forEach { permission ->
            val checkBox = android.widget.CheckBox(requireContext()).apply {
                text = permission.code
                isChecked = permission.code in assignedPermissions
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            permissionCheckboxes.add(checkBox)
            binding.settingsPermissionCheckboxContainer.addView(checkBox)
        }

        if (availablePermissions.isEmpty()) {
            binding.settingsPermissionSummary.text = "Role: ${selectedRole.name}\nNo permission catalog available"
        }
    }

    private fun updatePermissionSummaryForSelectedRole() {
        if (workspaceRoles.isEmpty()) {
            binding.settingsPermissionSummary.text = "No roles available"
            return
        }

        val selectedRoleIndex = binding.settingsPermissionRoleSpinner.selectedItemPosition.coerceIn(0, workspaceRoles.lastIndex)
        val selectedRole = workspaceRoles[selectedRoleIndex]
        val assignedPermissions = permissionByRole[selectedRole.id].orEmpty()
        val summaryText = if (assignedPermissions.isEmpty()) {
            "Role: ${selectedRole.name}\nNo permissions assigned"
        } else {
            val entries = assignedPermissions.joinToString(separator = "\n") { "- $it" }
            "Role: ${selectedRole.name}\n$entries"
        }
        binding.settingsPermissionSummary.text = summaryText
    }

    private fun saveSelectedRolePermissions() {
        val selectedRole = workspaceRoles.getOrNull(binding.settingsPermissionRoleSpinner.selectedItemPosition)
        if (selectedRole == null) {
            binding.settingsStatus.text = "Select a role to edit permissions."
            return
        }

        val permissionCodes = permissionCheckboxes
            .filter { it.isChecked }
            .map { it.text.toString() }
            .distinct()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.from("role_permissions")
                    .delete {
                        filter { eq("role_id", selectedRole.id) }
                    }

                if (permissionCodes.isNotEmpty()) {
                    val rows = permissionCodes.map { code ->
                        mapOf(
                            "role_id" to selectedRole.id,
                            "permission_code" to code,
                        )
                    }
                    supabaseClient.from("role_permissions").insert(rows)
                }
            }.onSuccess {
                permissionByRole = permissionByRole.toMutableMap().apply {
                    put(selectedRole.id, permissionCodes)
                }
                binding.settingsStatus.text = "Role permissions saved."
                updatePermissionSummaryForSelectedRole()
            }.onFailure { error ->
                binding.settingsStatus.text = error.message ?: "Could not save role permissions."
            }
        }
    }

    private fun assignSelectedRole() {
        val selectedMember = workspaceMembers.getOrNull(binding.settingsMemberSpinner.selectedItemPosition)
        val selectedRole = workspaceRoles.getOrNull(binding.settingsRoleSpinner.selectedItemPosition)
        if (selectedMember == null || selectedRole == null) {
            binding.settingsStatus.text = "Select both a member and a role first."
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.from("workspace_members")
                    .update(mapOf("role_id" to selectedRole.id)) {
                        filter { eq("id", selectedMember.id) }
                    }
            }.onSuccess {
                binding.settingsStatus.text = "Role assigned successfully."
                loadWorkspaceSettings()
            }.onFailure { error ->
                binding.settingsStatus.text = error.message ?: "Could not assign role."
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
