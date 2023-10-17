package io.zenandroid.onlinego.ui.screens.puzzle

import android.content.Context
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zenandroid.onlinego.data.model.Cell
import io.zenandroid.onlinego.data.model.Mark
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.ogs.MoveTree
import io.zenandroid.onlinego.data.model.ogs.PlayCategory
import io.zenandroid.onlinego.data.model.ogs.PuzzleRating
import io.zenandroid.onlinego.data.model.ogs.PuzzleSolution
import io.zenandroid.onlinego.data.model.local.Puzzle
import io.zenandroid.onlinego.data.repositories.PuzzleRepository
import io.zenandroid.onlinego.data.repositories.SettingsRepository
import io.zenandroid.onlinego.gamelogic.RulesManager
import io.zenandroid.onlinego.gamelogic.Util
import io.zenandroid.onlinego.gamelogic.Util.toCoordinateSet
import io.zenandroid.onlinego.ui.screens.puzzle.PuzzleDirectorySort.*
import io.zenandroid.onlinego.utils.addToDisposable
import io.zenandroid.onlinego.utils.recordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.plus
import java.time.Instant.now
import java.time.temporal.ChronoUnit.*

class TsumegoViewModel (
    private val puzzleRepository: PuzzleRepository,
    private val settingsRepository: SettingsRepository,
    private val puzzleId: Long
): ViewModel() {
    private val _state = MutableStateFlow(TsumegoState(
        boardTheme = settingsRepository.boardTheme,
        drawCoordinates = settingsRepository.showCoordinates,
    ))
    val state: StateFlow<TsumegoState> = _state
    val filterText by mutableStateOf(TextFieldValue())
    val sortField by mutableStateOf<PuzzleDirectorySort>(RatingSort(false))
    val resultCollections by mutableStateOf(TextFieldValue())
    var collectionPuzzles by mutableStateOf(emptyList<Puzzle>())
        private set
    private var cursor by mutableStateOf(0)

    private var moveReplyJob: Job? = null

    init {
        puzzleRepository.getPuzzle(puzzleId)
            .flowOn(Dispatchers.IO)
            .onEach { setPuzzle(it) }
            .catch { onError(it) }
            .launchIn(viewModelScope)

        fetchRating(puzzleId)
    }

    private fun setPuzzle(puzzle: Puzzle, attempts: Int = 1) {
        _state.update {
            it.copy(
                puzzle = puzzle,
                boardPosition = puzzle.puzzle.let {
                    RulesManager.buildPos(moves = emptyList(),
                        boardWidth = it.width, boardHeight = it.height,
                        whiteInitialState = it.initial_state.white.toCoordinateSet(),
                        blackInitialState = it.initial_state.black.toCoordinateSet(),
                        marks = it.move_tree.marks.orEmpty().map { markData ->
                            val cell = Cell(x = markData.x, y = markData.y)
                            Mark(cell, markData.marks.toString(), PlayCategory.LABEL)
                        }.toSet(),
                        nextToMove = when(puzzle.puzzle.initial_player) {
                            "white" -> StoneType.WHITE
                            "black" -> StoneType.BLACK
                            else -> StoneType.BLACK
                        }
                    )
                },
                attemptCount = attempts,
                sgfMoves = "",
                continueButtonVisible = false,
                retryButtonVisible = false,
                nodeStack = ArrayDeque(listOf(puzzle.puzzle.move_tree))
            )
        }

        fetchSolutions()
        if(collectionPuzzles.size == 0) {
            puzzleRepository.getPuzzleCollectionContents(puzzle.collection?.id ?: puzzle.puzzle.puzzle_collection.toLong())
                .flowOn(Dispatchers.IO)
                .onEach { setCollection(it) }
                .catch { onError(it) }
                .launchIn(viewModelScope)
        }
    }

    private fun setCollection(puzzles: List<Puzzle>) {
        collectionPuzzles = puzzles
        cursor = puzzles.indexOfFirst { it.id == _state.value.puzzle?.id }
    }

    val hasNextPuzzle: Boolean by derivedStateOf {
        cursor.let { it < collectionPuzzles.size.let { it - 1 } } == true
    }

    val hasPreviousPuzzle: Boolean by derivedStateOf {
        cursor.let { it > 0 } == true
    }

    fun nextPuzzle() {
        if(!hasNextPuzzle) return

        val index = cursor.let { it + 1 }
        cursor = index
        val puzzle = collectionPuzzles.get(index)
        setPuzzle(puzzle)
        fetchRating()
    }

    fun resetPuzzle() {
        val puzzle = _state.value.puzzle!!
        val attempts = _state.value.attemptCount + 1
        setPuzzle(puzzle, attempts)
    }

    fun previousPuzzle() {
        if(!hasPreviousPuzzle) return

        val index = cursor.let { it - 1 }
        cursor = index
        val puzzle = collectionPuzzles.get(index)
        setPuzzle(puzzle)
        fetchRating()
    }

    fun makeMove(move: Cell) {
        _state.value.nodeStack.let { stack ->
            val branches = stack.lastOrNull()?.branches ?: emptyList()
            val branch = branches.find {
                it.x == move.x && it.y == move.y
            } ?: branches.find { it.x == -1 || it.y == -1 }
            branch?.let { node ->
                moveReplyJob = viewModelScope.launch {
                    var position = state.value.boardPosition!!
                    position = (RulesManager.makeMove(position, position.nextToMove, move)
                        ?: run {
                            _state.value = state.value.copy(
                                hoveredCell = null
                            )
                            return@launch
                        }).copy(nextToMove = position.nextToMove.opponent)
                    val nodeStack = _state.value.nodeStack
                    nodeStack.addLast(node)
                    var moveString = _state.value.sgfMoves
                    moveString += Util.getSGFCoordinates(move)
                    node.branches?.randomOrNull()?.let { moveTree ->
                        val reply = Cell(moveTree.x, moveTree.y)
                        nodeStack.addLast(moveTree)
                        _state.update {
                            it.copy(
                                boardPosition = position.let { pos ->
                                    pos.copy(customMarks = pos.customMarks.plus(node.marks.orEmpty().map { markData ->
                                        val cell = Cell(x = markData.x, y = markData.y)
                                        Mark(cell, markData.marks.toString(), PlayCategory.LABEL)
                                    }))
                                },
                                nodeStack = nodeStack,
                                sgfMoves = moveString,
                                continueButtonVisible = if(moveTree.correct_answer == true) true
                                else _state.value.continueButtonVisible,
                            )
                        }
                        delay(600)
                        moveString += Util.getSGFCoordinates(reply)
                        position = (RulesManager.makeMove(position, position.nextToMove, reply)
                            ?: throw RuntimeException("Invalid move $moveTree")).copy(nextToMove = position.nextToMove.opponent)
                    }
                    _state.update {
                        it.copy(
                            boardPosition = position.let { pos ->
                                pos.copy(customMarks = pos.customMarks.plus(node.marks.orEmpty().map { markData ->
                                    val cell = Cell(x = markData.x, y = markData.y)
                                    Mark(cell, markData.marks.toString(), PlayCategory.LABEL)
                                }))
                            },
                            nodeStack = nodeStack,
                            sgfMoves = moveString,
                            continueButtonVisible = if(node.correct_answer == true) true
                            else _state.value.continueButtonVisible,
                            retryButtonVisible = true,
                            hoveredCell = null,
                        )
                    }
                }
            } ?: run launch@{
                var position = state.value.boardPosition!!
                position = (RulesManager.makeMove(position, position.nextToMove, move)
                    ?: run {
                        _state.update {
                            it.copy(hoveredCell = null)
                        }
                        return@launch
                    }).copy(nextToMove = position.nextToMove.opponent)
                val nodeStack = _state.value.nodeStack
                nodeStack.addLast(null)
                _state.update {
                    it.copy(
                        boardPosition = position,
                        nodeStack = nodeStack,
                        retryButtonVisible = true,
                        hoveredCell = null,
                    )
                }
            }
            Log.d("MoveTree", state.value.nodeStack.last()?.branches?.toString() ?: "")
        }
        if (_state.value.continueButtonVisible) {
            markSolved()
        }
    }

    fun addBoardHints() {
        _state.update {
            it.copy(boardInteractive = false)
        }
        fun isHappyPath(node: MoveTree): Boolean
            = node.correct_answer == true || node.branches?.any { isHappyPath(it) } == true
        val moves = _state.value.nodeStack.last()?.branches?.map {
            if(isHappyPath(it)) {
                if(it.correct_answer == true) {
                    Mark(Cell(it.x, it.y), "️", PlayCategory.IDEAL)
                } else {
                    Mark(Cell(it.x, it.y), "️", PlayCategory.GOOD)
                }
            } else {
                Mark(Cell(it.x, it.y), "", PlayCategory.MISTAKE)
            }
        } ?: emptyList()
        val position = _state.value.boardPosition!!.let {
            it.copy(customMarks = it.customMarks.plus(moves))
        }
        _state.update {
            it.copy(
                boardPosition = position,
                boardInteractive = true
            )
        }
    }

    fun markSolved(): Job {
        val record = _state.value.let { PuzzleSolution(
            puzzle = it.puzzle!!.id,
            time_elapsed = it.startTime?.let { MILLIS.between(it, now()) } ?: 0,
            attempts = it.attemptCount,
            solution = it.sgfMoves,
        ) }

        return puzzleRepository.markPuzzleSolved(_state.value.puzzle?.id!!, record)
            .flowOn(Dispatchers.IO)
            .onEach { updateSolutions(listOf(record)) }
            .catch { onError(it) }
            .launchIn(viewModelScope)
    }

    private fun fetchSolutions() {
        updateSolutions()
        puzzleRepository.getPuzzleSolution(_state.value.puzzle?.id!!)
            .flowOn(Dispatchers.IO)
            .onEach { updateSolutions(it) }
            .catch { onError(it) }
            .launchIn(viewModelScope)
    }

    private fun updateSolutions(solution: List<PuzzleSolution>? = null) {
        _state.update {
            it.copy(
                solutions = solution?.let { _state.value.solutions.plus(it) } ?: emptyList()
            )
        }
    }

    fun rate(value: Int) {
        puzzleRepository.ratePuzzle(_state.value.puzzle?.id!!, value)
            .flowOn(Dispatchers.IO)
            .onEach { updateRating(value) }
            .catch { onError(it) }
            .launchIn(viewModelScope)
    }

    private fun fetchRating(id: Long? = null) {
        puzzleRepository.getPuzzleRating(id ?: _state.value.puzzle?.id!!)
            .flowOn(Dispatchers.IO)
            .onEach { updateRating(it.rating) }
            .catch { updateRating(-1) }
            .launchIn(viewModelScope)
    }

    private fun updateRating(value: Int) {
        _state.update {
            it.copy(
                rating = PuzzleRating(rating = value)
            )
        }
    }

    private fun onError(t: Throwable) {
        Log.e(this::class.java.canonicalName, t.message, t)
        recordException(t)
    }
}