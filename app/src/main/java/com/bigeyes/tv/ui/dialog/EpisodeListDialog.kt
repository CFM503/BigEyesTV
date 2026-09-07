package com.bigeyes.tv.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bigeyes.tv.R
import com.bigeyes.tv.databinding.DialogEpisodeListBinding
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.controller.PlaybackController
import com.bigeyes.tv.player.model.Episode

/**
 * Android TV Remote-friendly episode selection dialog with Grid layout and autofocus.
 */
class EpisodeListDialog(
    context: Context,
    private val controller: PlaybackController
) : Dialog(context) {

    private lateinit var binding: DialogEpisodeListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogEpisodeListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val queue = controller.episodeQueue
        val episodes = queue.episodes
        val currentIndex = queue.currentIndex
        val session = controller.session.value

        binding.tvDialogSeriesTitle.text = session.seriesTitle ?: session.currentEpisode?.seriesTitle ?: ""
        binding.tvDialogEpisodeCount.text = "共 ${episodes.size} 集"

        val spanCount = if (episodes.size > 8) 6 else 4
        binding.recyclerEpisodes.layoutManager = GridLayoutManager(context, spanCount)
        val adapter = EpisodeAdapter(episodes, currentIndex) { selectedEp ->
            controller.dispatch(PlaybackCommand.PlayEpisode(selectedEp.episodeIndex))
            dismiss()
        }
        binding.recyclerEpisodes.adapter = adapter

        // Focus current episode view after layout
        binding.recyclerEpisodes.post {
            val viewHolder = binding.recyclerEpisodes.findViewHolderForAdapterPosition(currentIndex)
            viewHolder?.itemView?.requestFocus()
        }
    }

    private class EpisodeAdapter(
        private val episodes: List<Episode>,
        private val currentIndex: Int,
        private val onSelect: (Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode_grid, parent, false)
            return EpisodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
            val ep = episodes[position]
            val isCurrent = position == currentIndex

            holder.tvNumber.text = if (ep.episodeNumber > 0) "第 ${ep.episodeNumber} 集" else "第 ${position + 1} 集"
            if (ep.episodeTitle.isNotBlank()) {
                holder.tvTitle.visibility = View.VISIBLE
                holder.tvTitle.text = ep.episodeTitle
            } else {
                holder.tvTitle.visibility = View.GONE
            }

            if (isCurrent) {
                holder.tvNumber.setTextColor(Color.parseColor("#FFD700"))
                holder.tvTitle.setTextColor(Color.parseColor("#FFD700"))
            } else {
                holder.tvNumber.setTextColor(Color.WHITE)
                holder.tvTitle.setTextColor(Color.parseColor("#B0BEC5"))
            }

            holder.itemView.setOnClickListener {
                onSelect(ep)
            }
        }

        override fun getItemCount(): Int = episodes.size

        class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvNumber: TextView = itemView.findViewById(R.id.tvEpisodeNumber)
            val tvTitle: TextView = itemView.findViewById(R.id.tvEpisodeTitle)
        }
    }
}
