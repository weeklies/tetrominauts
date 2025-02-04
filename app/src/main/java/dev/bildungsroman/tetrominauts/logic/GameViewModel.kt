package dev.bildungsroman.tetrominauts.logic

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bildungsroman.tetrominauts.logic.DropBlock.Companion.Empty
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModelFactory constructor(private val sharedPreferences: SharedPreferences) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GameViewModel(sharedPreferences) as T
    }
}

class GameViewModel(private val prefs: SharedPreferences) : ViewModel() {

    private val _viewState: MutableState<ViewState> =
        mutableStateOf(
            ViewState(
                matrix =
                    Pair(
                        prefs.getInt(gridWidth, MatrixWidth),
                        prefs.getInt(gridHeight, MatrixHeight),
                    ),
                isDarkMode = prefs.getBoolean(isDarkMode, true),
                isMute = prefs.getBoolean(isMute, false),
                useNauts = prefs.getBoolean(useNauts, true),
                useGhostBlock = prefs.getBoolean(useGhostBlock, true),
                showGridOutline = prefs.getBoolean(showGridOutline, false),
                gameSpeed = prefs.getInt(gameSpeed, 3),
                nautProbability = prefs.getInt(nautProbability, 6),
                showBackgroundArt = prefs.getBoolean(showBackgroundArt, true),
            )
        )
    val viewState: State<ViewState> = _viewState

    fun dispatch(action: Action) = reduce(viewState.value, action)

    private fun reduce(state: ViewState, action: Action) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                emit(
                    when (action) {
                        // Game Mechanics
                        Action.Reset ->
                            run {
                                if (
                                    state.gameStatus == GameStatus.Onboard ||
                                        state.gameStatus == GameStatus.GameOver
                                ) {
                                    return@run state.copy(
                                        gameStatus = GameStatus.Running,
                                        blocks = emptyList(),
                                        dropBlock = Empty,
                                        dropBlockReserve = emptyList(),
                                        ghostBlock = Empty,
                                        score = 0,
                                        line = 0,
                                    )
                                }
                                state.copy(gameStatus = GameStatus.ScreenClearing).also {
                                    launch {
                                        clearScreen(state = state)
                                        emit(
                                            state.copy(
                                                gameStatus = GameStatus.Onboard,
                                                blocks = emptyList(),
                                                dropBlock = Empty,
                                                dropBlockReserve = emptyList(),
                                                ghostBlock = Empty,
                                                score = 0,
                                                line = 0,
                                            )
                                        )
                                    }
                                }
                            }
                        Action.Pause ->
                            if (state.isRunning) {
                                state.copy(gameStatus = GameStatus.Paused)
                            } else state
                        Action.Resume ->
                            if (state.isPaused) {
                                state.copy(gameStatus = GameStatus.Running)
                            } else state
                        is Action.Move ->
                            run {
                                if (!state.isRunning) return@run state
                                SoundUtil.play(state.isMute, SoundType.Move)
                                val offset = action.direction.toOffset()
                                val dropBlock = state.dropBlock.moveBy(offset)
                                if (dropBlock.isValidInMatrix(state.blocks, state.matrix)) {
                                    state.copy(
                                        dropBlock = dropBlock,
                                        ghostBlock = determineDroppedBlock(dropBlock, state)
                                    )
                                } else {
                                    state
                                }
                            }
                        Action.Rotate ->
                            run {
                                if (!state.isRunning) return@run state
                                SoundUtil.play(state.isMute, SoundType.Rotate)
                                val dropBlock = state.dropBlock.rotate().adjustOffset(state.matrix)
                                if (dropBlock.isValidInMatrix(state.blocks, state.matrix)) {
                                    state.copy(
                                        dropBlock = dropBlock,
                                        ghostBlock = determineDroppedBlock(dropBlock, state)
                                    )
                                } else {
                                    state
                                }
                            }
                        Action.Drop ->
                            run {
                                if (!state.isRunning) return@run state
                                SoundUtil.play(state.isMute, SoundType.Drop)
                                state.copy(
                                    dropBlock = determineDroppedBlock(state.dropBlock, state),
                                    ghostBlock = Empty
                                )
                            }
                        Action.GameTick ->
                            run {
                                if (!state.isRunning) return@run state

                                // DropBlock continues falling
                                if (state.dropBlock != Empty) {
                                    val dropBlock =
                                        state.dropBlock.moveBy(Direction.Down.toOffset())
                                    if (dropBlock.isValidInMatrix(state.blocks, state.matrix)) {
                                        return@run state.copy(
                                            dropBlock = dropBlock,
                                            ghostBlock = determineDroppedBlock(dropBlock, state)
                                        )
                                    }
                                }

                                // GameOver
                                if (!state.dropBlock.isValidInMatrix(state.blocks, state.matrix)) {
                                    SoundUtil.play(state.isMute, SoundType.GameOver)
                                    // Clear screen silently, allowing Game Over to play.
                                    val isMuted = state.isMute
                                    return@run state
                                        .copy(gameStatus = GameStatus.ScreenClearing, isMute = true)
                                        .also {
                                            launch {
                                                emit(
                                                    clearScreen(state = it)
                                                        .copy(
                                                            gameStatus = GameStatus.GameOver,
                                                            isMute = isMuted
                                                        )
                                                )
                                            }
                                        }
                                }

                                // Next DropBlock
                                val (updatedBlocks, clearedLines) =
                                    updateBlocks(
                                        state.blocks,
                                        state.dropBlock,
                                        matrix = state.matrix
                                    )
                                val (noClear, clearing, cleared) = updatedBlocks
                                val newState =
                                    state.copy(
                                        dropBlock = state.dropBlockNext,
                                        dropBlockReserve =
                                            (state.dropBlockReserve - state.dropBlockNext).takeIf {
                                                it.isNotEmpty()
                                            }
                                                ?: generateDropAndNautBlocks(
                                                    state.matrix,
                                                    state.useNauts,
                                                    state.nautProbability,
                                                ),
                                        score =
                                            state.score +
                                                calculateScore(clearedLines) +
                                                if (state.dropBlock != Empty) ScoreEveryDropBlock
                                                else 0,
                                        line = state.line + clearedLines
                                    )
                                if (clearedLines != 0) { // has cleared lines
                                    SoundUtil.play(state.isMute, SoundType.Clean)
                                    state.copy(gameStatus = GameStatus.LineClearing).also {
                                        launch {
                                            // animate the clearing lines
                                            repeat(5) {
                                                emit(
                                                    state.copy(
                                                        gameStatus = GameStatus.LineClearing,
                                                        dropBlock = Empty,
                                                        ghostBlock = Empty,
                                                        blocks =
                                                            if (it % 2 == 0) noClear else clearing
                                                    )
                                                )
                                                delay(100)
                                            }
                                            // delay emit new state
                                            emit(
                                                newState.copy(
                                                    blocks = cleared,
                                                    gameStatus = GameStatus.Running
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    val resWithoutGhostBlock = newState.copy(blocks = noClear)
                                    resWithoutGhostBlock.copy(
                                        ghostBlock =
                                            determineDroppedBlock(
                                                resWithoutGhostBlock.dropBlock,
                                                resWithoutGhostBlock
                                            )
                                    )
                                }
                            }
                        Action.Mute -> {
                            val newIsMute = !state.isMute
                            if (state.isMute) SoundUtil.resume(newIsMute) else SoundUtil.pause()
                            prefs.edit().putBoolean(isMute, newIsMute).apply()
                            state.copy(isMute = newIsMute)
                        }
                        Action.ToggleInfoDialog ->
                            state.copy(isInfoDialogOpen = !state.isInfoDialogOpen)
                        Action.DarkMode -> {
                            val value = !state.isDarkMode
                            prefs.edit().putBoolean(isDarkMode, value).apply()
                            state.copy(isDarkMode = value)
                        }
                        // Game Settings
                        Action.UseNauts -> {
                            val value = !state.useNauts
                            prefs.edit().putBoolean(useNauts, value).apply()
                            state.copy(useNauts = value)
                        }
                        Action.UseGhostBlock -> {
                            val value = !state.useGhostBlock
                            prefs.edit().putBoolean(useGhostBlock, value).apply()
                            state.copy(useGhostBlock = value)
                        }
                        Action.ShowGridOutline -> {
                            val value = !state.showGridOutline
                            prefs.edit().putBoolean(showGridOutline, value).apply()
                            state.copy(showGridOutline = value)
                        }
                        Action.ShowBackgroundArt -> {
                            val value = !state.showBackgroundArt
                            prefs.edit().putBoolean(showBackgroundArt, value).apply()
                            state.copy(showBackgroundArt = value)
                        }
                        is Action.GridHeight -> {
                            val value = action.v
                            prefs.edit().putInt(gridHeight, value).apply()
                            state.copy(matrix = Pair(state.matrix.first, value))
                        }
                        is Action.GridWidth -> {
                            val value = action.v
                            prefs.edit().putInt(gridWidth, value).apply()
                            state.copy(matrix = Pair(value, state.matrix.second))
                        }
                        is Action.GameSpeed -> {
                            val value = action.v
                            prefs.edit().putInt(gameSpeed, value).apply()
                            state.copy(gameSpeed = value)
                        }
                        is Action.NautProbability -> {
                            val value = action.v
                            prefs.edit().putInt(nautProbability, value).apply()
                            state.copy(nautProbability = value)
                        }
                    }
                )
            }
        }
    }

    private fun determineDroppedBlock(db: DropBlock, state: ViewState): DropBlock {
        if (db == Empty) return Empty
        var i = 0
        while (db.moveBy(0 to ++i).isValidInMatrix(state.blocks, state.matrix)) { // nothing to do
        }
        return db.moveBy(0 to i - 1)
    }

    private suspend fun clearScreen(state: ViewState): ViewState {
        SoundUtil.play(state.isMute, SoundType.Start)
        val xRange = 0 until state.matrix.first
        var newState = state

        (state.matrix.second downTo 0).forEach { y ->
            emit(
                state.copy(
                    gameStatus = GameStatus.ScreenClearing,
                    blocks =
                        state.blocks +
                            Block.of(
                                xRange,
                                y until state.matrix.second,
                            )
                )
            )
            delay(50)
        }
        (0..state.matrix.second).forEach { y ->
            emit(
                state
                    .copy(
                        gameStatus = GameStatus.ScreenClearing,
                        blocks =
                            Block.of(
                                xRange,
                                y until state.matrix.second,
                            ),
                        dropBlock = Empty,
                        ghostBlock = Empty
                    )
                    .also { newState = it }
            )
            delay(50)
        }
        return newState
    }

    private fun emit(state: ViewState) {
        _viewState.value = state
    }

    /**
     * Return a [Triple] to store clear-info for blocks:
     * - [Triple.first]: Blocks before line clearing (Current blocks plus DropBlock)
     * - [Triple.second]: Blocks after line cleared but not offset (blocks minus lines should be
     * cleared)
     * - [Triple.third]: Blocks after line cleared (after blocks offset)
     */
    private fun updateBlocks(
        curBlocks: List<Block>,
        dropBlock: DropBlock,
        matrix: Pair<Int, Int>
    ): Pair<Triple<List<Block>, List<Block>, List<Block>>, Int> {
        val blocks = (curBlocks + Block.of(dropBlock))
        val map = mutableMapOf<Float, MutableSet<Float>>()
        blocks.forEach { map.getOrPut(it.location.y) { mutableSetOf() }.add(it.location.x) }
        var clearing = blocks
        var cleared = blocks
        val clearLines =
            map.entries
                .sortedBy { it.key }
                .filter { it.value.size == matrix.first }
                .map { it.key }
                .onEach { line ->
                    // clear line
                    clearing = clearing.filter { it.location.y != line }
                    // clear line and then offset brick
                    cleared =
                        cleared
                            .filter { it.location.y != line }
                            .map { if (it.location.y < line) it.offsetBy(0 to 1) else it }
                }

        return Triple(blocks, clearing, cleared) to clearLines.size
    }

    data class ViewState(
        val blocks: List<Block> = emptyList(),
        val dropBlock: DropBlock = Empty,
        val dropBlockReserve: List<DropBlock> = emptyList(),
        val ghostBlock: DropBlock = Empty,
        val matrix: Pair<Int, Int>,
        val gameStatus: GameStatus = GameStatus.Onboard,
        val score: Int = 0,
        val line: Int = 0,
        val isMute: Boolean,
        val isInfoDialogOpen: Boolean = false,
        val isDarkMode: Boolean,
        val showBackgroundArt: Boolean,

        // Changed by Game Settings
        val useNauts: Boolean,
        val useGhostBlock: Boolean,
        val showGridOutline: Boolean,
        val nautProbability: Int, // this will be converted to a decimal. e.g 5 will be 0.6
        val gameSpeed: Int,
    ) {
        val level: Int
            get() = min(10, 1 + line / 20)

        val dropBlockNext: DropBlock
            get() = dropBlockReserve.firstOrNull() ?: Empty

        val isPaused
            get() = gameStatus == GameStatus.Paused

        val isRunning
            get() = gameStatus == GameStatus.Running
    }
}

sealed interface Action {
    data class Move(val direction: Direction) : Action
    object Reset : Action
    object Pause : Action
    object Resume : Action
    object Rotate : Action
    object Drop : Action
    object GameTick : Action
    object Mute : Action
    object ToggleInfoDialog : Action
    object DarkMode : Action
    object UseNauts : Action
    object UseGhostBlock : Action
    object ShowGridOutline : Action
    object ShowBackgroundArt : Action
    data class NautProbability(val v: Int) : Action
    data class GridWidth(val v: Int) : Action
    data class GameSpeed(val v: Int) : Action
    data class GridHeight(val v: Int) : Action
}

enum class GameStatus {
    Onboard, // 游戏欢迎页
    Running, // 游戏进行中
    LineClearing, // 消行动画中
    Paused, // 游戏暂停
    ScreenClearing, // 清屏动画中
    GameOver // 游戏结束
}

private const val MatrixWidth = 12
private const val MatrixHeight = 24
