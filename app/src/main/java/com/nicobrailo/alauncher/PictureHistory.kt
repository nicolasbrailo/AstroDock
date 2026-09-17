package com.nicobrailo.alauncher

// The last `capacity` pictures shown, and which of them is on screen, so the
// user can swipe back through them.
//
// Going back stops at the oldest kept picture. Going forward walks the kept
// pictures again; only once the newest one is on screen does the caller need to
// fetch a new picture and add() it, which evicts the oldest if full.
//
// Not thread safe: use it from the main thread only.
class PictureHistory<T>(private val capacity: Int) {
    init {
        require(capacity > 0) { "Invalid capacity: $capacity" }
    }

    private val items = ArrayDeque<T>(capacity)
    private var pos = -1 // Index of the picture on screen, -1 if empty

    val current: T? get() = items.getOrNull(pos)

    // True if the newest picture is on screen (or there is none), so moving
    // forward needs a new picture
    val atNewest: Boolean get() = pos == items.size - 1

    // The picture back() would move to, without moving
    fun peekBack(): T? = if (pos > 0) items[pos - 1] else null

    // The picture forward() would move to, without moving
    fun peekForward(): T? = if (atNewest) null else items[pos + 1]

    // Moves to the previous picture and returns it, or returns null (and stays
    // put) if already at the oldest kept one
    fun back(): T? {
        if (pos <= 0) return null
        return items[--pos]
    }

    // Moves to the next kept picture and returns it, or returns null if already
    // at the newest one (then fetch a new picture and add() it)
    fun forward(): T? {
        if (atNewest) return null
        return items[++pos]
    }

    // Adds a new picture after the newest and puts it on screen. Only valid when
    // atNewest.
    fun add(item: T) {
        check(atNewest) { "Adding a picture while browsing back through history" }
        if (items.size == capacity) items.removeFirst()
        items.addLast(item)
        pos = items.size - 1
    }

    fun clear() {
        items.clear()
        pos = -1
    }
}
