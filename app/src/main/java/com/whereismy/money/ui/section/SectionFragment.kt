package com.whereismy.money.ui.section

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.whereismy.money.R
import com.whereismy.money.databinding.FragmentSectionBinding

class SectionFragment : Fragment() {

    private var _binding: FragmentSectionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSectionBinding.inflate(inflater, container, false)
        binding.sectionTitle.text = findNavController().currentDestination?.label
        binding.sectionDescription.setText(R.string.section_planned)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
