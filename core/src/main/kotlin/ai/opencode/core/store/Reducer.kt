package ai.opencode.core.store

/**
 * What a reducer returns: the next state and the effect to run (TCA's
 * `reduce(into: &state, action:) -> Effect`, with `inout` replaced by an immutable copy).
 *
 * Porting `state.title = t; return .none` is `return Reduced(state.copy(title = t))`.
 * For many sequential mutations use a local `var next = state` and reassign.
 */
data class Reduced<out S, out A>(val state: S, val effect: Effect<A> = Effect.none())

/**
 * Port of TCA's `Reducer`. A reducer is a pure function of (state, action); everything
 * else (I/O, timers, persistence) is an [Effect]. Dependencies are read with
 * `dependency(Key)` / `by Dependency(Key)`, which resolve the store's values.
 *
 * Rules carried over from iOS (`docs/architecture.md` §1):
 * - Never call `reduce` of the same reducer recursively from inside `reduce`; extract a
 *   private helper that returns a [Reduced] instead.
 * - No protocol decoding in reducers; `:server` decodes.
 * - Mutate shared keys only in reducers, only when the value changes ([ai.opencode.core.sharing.Shared.set]).
 */
fun interface Reducer<S, A> {
    fun reduce(state: S, action: A): Reduced<S, A>
}

/** Runs reducers in order over the same action, merging their effects (TCA's `CombineReducers`/body). */
fun <S, A> combine(vararg reducers: Reducer<S, A>): Reducer<S, A> = Reducer { state, action ->
    var current = state
    val effects = ArrayList<Effect<A>>(reducers.size)
    for (reducer in reducers) {
        val reduced = reducer.reduce(current, action)
        current = reduced.state
        effects += reduced.effect
    }
    Reduced(current, Effect.merge(effects))
}

/**
 * Port of TCA's `Scope(state:action:)`: runs [child] on a slice of the parent state when the
 * parent action carries a child action. Put it before the parent's own reducer in [combine],
 * as TCA's body ordering does.
 */
fun <S, A, CS, CA> scope(
    state: (S) -> CS,
    setState: (S, CS) -> S,
    action: (A) -> CA?,
    embed: (CA) -> A,
    child: Reducer<CS, CA>,
): Reducer<S, A> = Reducer { parent, parentAction ->
    val childAction = action(parentAction) ?: return@Reducer Reduced(parent)
    val reduced = child.reduce(state(parent), childAction)
    Reduced(setState(parent, reduced.state), reduced.effect.map(embed))
}

/**
 * Port of TCA's `.ifLet(\.$child, action: \.child)` for presented (`@Presents`) child state.
 *
 * - Child actions while the child is absent are ignored.
 * - The parent reduces [PresentationAction.Dismiss] with the child still present; the child is
 *   cleared afterwards unless the parent replaced it (TCA order).
 * - When the child goes away or is replaced by a child with a different [childID] (e.g. another
 *   session), every effect the old child started is cancelled, so presented work lives and dies
 *   with its presentation. Effects that must outlive dismissal belong in the parent, or use the
 *   plain-optional [ifLet] overload.
 *
 * [id] distinguishes this presentation slot from others in the same store; pass a stable
 * private object. [childID] is the child's identity (TCA's `Identifiable` id); default: its class.
 */
fun <S, A, CS : Any, CA> ifLet(
    id: Any,
    parent: Reducer<S, A>,
    state: (S) -> CS?,
    setState: (S, CS?) -> S,
    action: (A) -> PresentationAction<CA>?,
    embed: (PresentationAction<CA>) -> A,
    child: Reducer<CS, CA>,
    childID: (CS) -> Any? = { it::class },
): Reducer<S, A> = Reducer { current, parentAction ->
    val presentation = action(parentAction)
    var next = current
    val effects = ArrayList<Effect<A>>(3)
    val before = state(current)
    val beforeID = before?.let { PresentationCancelID(id, childID(it)) }
    if (presentation is PresentationAction.Presented && before != null) {
        val reduced = child.reduce(before, presentation.action)
        next = setState(next, reduced.state)
        effects += reduced.effect
            .map { embed(PresentationAction.Presented(it)) }
            .cancellable(beforeID!!)
    }
    val childBeforeParent = state(next)
    val parentReduced = parent.reduce(next, parentAction)
    next = parentReduced.state
    effects += parentReduced.effect
    if (presentation is PresentationAction.Dismiss && state(next) === childBeforeParent) {
        next = setState(next, null)
    }
    val after = state(next)
    val afterID = after?.let { PresentationCancelID(id, childID(it)) }
    if (beforeID != null && beforeID != afterID) effects += Effect.cancel(beforeID)
    Reduced(next, Effect.merge(effects))
}

/**
 * Port of TCA's plain-optional `.ifLet(\.child, action: \.child)` (no `@Presents`): runs [child]
 * while its state exists; nothing is cancelled when it goes away (iOS `AppFeature.home`,
 * `SessionFeature.sessionSheet`, whose effects intentionally outlive dismissal).
 */
fun <S, A, CS : Any, CA> ifLet(
    parent: Reducer<S, A>,
    state: (S) -> CS?,
    setState: (S, CS) -> S,
    action: (A) -> CA?,
    embed: (CA) -> A,
    child: Reducer<CS, CA>,
): Reducer<S, A> = Reducer { current, parentAction ->
    var next = current
    val effects = ArrayList<Effect<A>>(2)
    val childAction = action(parentAction)
    val childState = state(next)
    if (childAction != null && childState != null) {
        val reduced = child.reduce(childState, childAction)
        next = setState(next, reduced.state)
        effects += reduced.effect.map(embed)
    }
    val parentReduced = parent.reduce(next, parentAction)
    effects += parentReduced.effect
    Reduced(parentReduced.state, Effect.merge(effects))
}

private data class PresentationCancelID(val slot: Any, val child: Any?)

/** Port of TCA's `PresentationAction`: a presented child's action, or its dismissal. */
sealed interface PresentationAction<out A> {
    data class Presented<A>(val action: A) : PresentationAction<A>
    data object Dismiss : PresentationAction<Nothing>
}

/**
 * Port of TCA's `.forEach(\.items, action: \.items)` over an identified list. Unlike TCA's
 * `IdentifiedArray` `forEach`, child effects are cancelled when their element is removed, so a
 * removed element's late actions (delegate actions included) never arrive. (iOS
 * `HomeFeature+Composition.swift` cancels leftovers by hand for the same reason.)
 */
fun <S, A, ID : Any, CS, CA> forEach(
    slot: Any,
    parent: Reducer<S, A>,
    elements: (S) -> List<CS>,
    setElements: (S, List<CS>) -> S,
    elementID: (CS) -> ID,
    action: (A) -> Pair<ID, CA>?,
    embed: (ID, CA) -> A,
    child: Reducer<CS, CA>,
): Reducer<S, A> = Reducer { current, parentAction ->
    var next = current
    val effects = ArrayList<Effect<A>>(3)
    action(parentAction)?.let { (id, childAction) ->
        val list = elements(next)
        val index = list.indexOfFirst { elementID(it) == id }
        if (index >= 0) {
            val reduced = child.reduce(list[index], childAction)
            next = setElements(next, list.toMutableList().also { it[index] = reduced.state })
            effects += reduced.effect.map { embed(id, it) }.cancellable(ForEachCancelID(slot, id))
        }
    }
    val before = elements(current).map(elementID).toSet()
    val parentReduced = parent.reduce(next, parentAction)
    next = parentReduced.state
    effects += parentReduced.effect
    val removed = before - elements(next).map(elementID).toSet()
    if (removed.isNotEmpty()) effects += Effect.cancel(removed.map { ForEachCancelID(slot, it) })
    Reduced(next, Effect.merge(effects))
}

private data class ForEachCancelID(val slot: Any, val id: Any)

/**
 * Port of TCA's `.onChange(of:)`: after [this] reducer runs, when [select] differs between the
 * old and new state, runs [onChange] with the old and new values. Key it on a small value
 * with cheap equality (iOS keys `.onChange` on small `Equatable` structs).
 */
fun <S, A, V> Reducer<S, A>.onChange(
    select: (S) -> V,
    onChange: (old: V, new: V, state: S) -> Reduced<S, A>,
): Reducer<S, A> = Reducer { state, action ->
    val old = select(state)
    val reduced = this.reduce(state, action)
    val new = select(reduced.state)
    if (old == new) {
        reduced
    } else {
        val changed = onChange(old, new, reduced.state)
        Reduced(changed.state, Effect.merge(reduced.effect, changed.effect))
    }
}

/** A reducer that does nothing (TCA's `EmptyReducer`). */
fun <S, A> emptyReducer(): Reducer<S, A> = Reducer { state, _ -> Reduced(state) }
