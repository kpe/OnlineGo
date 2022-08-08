package io.zenandroid.onlinego.ui.screens.mygames

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.ui.screens.game.GAME_HEIGHT
import io.zenandroid.onlinego.ui.screens.game.GAME_ID
import io.zenandroid.onlinego.ui.screens.game.GAME_WIDTH
import io.zenandroid.onlinego.ui.screens.main.MainActivity
import io.zenandroid.onlinego.ui.screens.mygames.Action.GameSelected
import io.zenandroid.onlinego.ui.theme.OnlineGoTheme
import io.zenandroid.onlinego.utils.WhatsNewUtils
import io.zenandroid.onlinego.utils.rememberStateWithLifecycle
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Created by alex on 05/11/2017.
 */
class MyGamesFragment : Fragment() {

    private val viewModel: MyGamesViewModel by viewModel()
    private var analytics = OnlineGoApplication.instance.analytics

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                DisposeOnLifecycleDestroyed(viewLifecycleOwner)
            )
            setContent {
                OnlineGoTheme {
                    val state by rememberStateWithLifecycle(viewModel.state)

                    MyGamesScreen(state, ::onAction)

                    if(state.alertDialogText != null) {
                        AlertDialog(
                            title = { state.alertDialogTitle?.let { Text(it) } },
                            text = { state.alertDialogText?.let { Text(it) } },
                            confirmButton = {
                                Button(onClick = { onAction(Action.DismissAlertDialog) }) {
                                    Text("OK")
                                }
                            },
                            onDismissRequest = { onAction(Action.DismissAlertDialog) }
                        )
                    }
                    if(state.gameNavigationPending != null) {
                        LaunchedEffect(state.gameNavigationPending) {
                            navigateToGameScreen(state.gameNavigationPending!!)
                            viewModel.onAction(Action.GameNavigationConsumed)
                        }
                    }
                    if(state.whatsNewDialogVisible) {
                        AlertDialog(
                            onDismissRequest = { onAction(Action.DismissWhatsNewDialog) },
                            dismissButton = {
                                TextButton(onClick = { onAction(Action.DismissWhatsNewDialog) }) {
                                    Text("OK")
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { onAction(Action.SupportClicked) }) {
                                    Text("SUPPORT")
                                }
                            },
                            text = { Text(WhatsNewUtils.whatsNewTextAnnotated) }
                        )
                    }
                }
            }
        }

    private fun onAction(action: Action) {
        when(action) {
            Action.CustomGame -> {
                analytics.logEvent("friend_item_clicked", null)
                (activity as MainActivity).onCustomGameSearch()
            }
            Action.PlayAgainstAI -> {
                analytics.logEvent("localai_item_clicked", null)
                view?.findNavController()?.navigate(R.id.action_myGamesFragment_to_aiGameFragment)
            }
            Action.PlayOnline -> {
                analytics.logEvent("automatch_item_clicked", null)
                (activity as MainActivity).onAutoMatchSearch()
            }
            Action.SupportClicked -> {
                analytics.logEvent("support_whats_new_clicked", null)
                (activity as MainActivity).onNavigateToSupport()
            }
            is GameSelected -> {
                val game = action.game
                analytics.logEvent("clicked_game", Bundle().apply {
                    putLong("GAME_ID", game.id)
                    putBoolean("ACTIVE_GAME", game.ended == null)
                })
                navigateToGameScreen(game)
            }
            else -> viewModel.onAction(action)
        }
    }

    private fun navigateToGameScreen(game: Game) {
        view?.findNavController()?.navigate(
            R.id.gameFragment,
            bundleOf(
                GAME_ID to game.id,
                GAME_WIDTH to game.width,
                GAME_HEIGHT to game.height,
            ),
            NavOptions.Builder()
                .setLaunchSingleTop(true)
                .build()
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(Action.ViewResumed)
        analytics.setCurrentScreen(requireActivity(), javaClass.simpleName, null)
    }
}
