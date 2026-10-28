package com.syntra.os.memory

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Node(
    val id: String = UUID.randomUUID().toString().take(8),
    val label: String,
    val type: String = "concept", // person, project, story, task, concept, ...
    val notes: String = "",
)

@Serializable
data class Edge(val from: String, val to: String, val relation: String = "related_to")

/** Immutable knowledge graph shared by the user and every model. */
@Serializable
data class Graph(val nodes: List<Node> = emptyList(), val edges: List<Edge> = emptyList()) {

    fun find(labelOrId: String): Node? =
        nodes.firstOrNull { it.id == labelOrId } ?: nodes.firstOrNull { it.label.equals(labelOrId, ignoreCase = true) }

    /** Adds a node or merges notes into an existing one with the same label (agents repeat themselves). */
    fun upsert(label: String, type: String, notes: String): Pair<Graph, Node> {
        val existing = find(label)
        if (existing != null) {
            val merged = existing.copy(
                type = if (existing.type == "concept") type else existing.type,
                notes = listOf(existing.notes, notes).filter { it.isNotBlank() }.distinct().joinToString("\n"),
            )
            return copy(nodes = nodes.map { if (it.id == existing.id) merged else it }) to merged
        }
        val n = Node(label = label.trim(), type = type, notes = notes)
        return copy(nodes = nodes + n) to n
    }

    fun link(a: String, b: String, relation: String): Graph {
        val e = Edge(a, b, relation)
        return if (a == b || e in edges) this else copy(edges = edges + e)
    }

    fun update(node: Node) = copy(nodes = nodes.map { if (it.id == node.id) node else it })

    fun remove(id: String) = Graph(nodes.filterNot { it.id == id }, edges.filterNot { it.from == id || it.to == id })

    fun neighbors(id: String): List<Pair<Edge, Node>> = edges.mapNotNull { e ->
        val other = when (id) { e.from -> e.to; e.to -> e.from; else -> null } ?: return@mapNotNull null
        nodes.firstOrNull { it.id == other }?.let { e to it }
    }

    fun search(q: String): List<Node> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return nodes.filter { t in it.label.lowercase() || t in it.notes.lowercase() || t == it.type }
    }

    /**
     * Community detection by label propagation (Graphify uses Leiden).
     * shortcut: label propagation is cruder than Leiden; upgrade if graphs reach thousands of nodes.
     */
    fun communities(): Map<String, Int> {
        val adj = nodes.associate { it.id to mutableListOf<String>() }
        edges.forEach { adj[it.from]?.add(it.to); adj[it.to]?.add(it.from) }
        val label = nodes.associate { it.id to it.id }.toMutableMap()
        for (round in 0 until 15) {
            var changed = false
            for (n in nodes.sortedBy { it.id }) {
                val ns = adj[n.id].orEmpty()
                if (ns.isEmpty()) continue
                val best = ns.groupingBy { label[it]!! }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
                if (best != label[n.id]) { label[n.id] = best; changed = true }
            }
            if (!changed) break
        }
        val ids = label.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .mapIndexed { i, e -> e.key to i }.toMap()
        return label.mapValues { ids[it.value]!! }
    }

    /** Compact text for model context. */
    fun describe(limit: Int = 40): String = nodes.take(limit).joinToString("\n") { n ->
        val rel = neighbors(n.id).take(6).joinToString(", ") { (e, o) -> "${e.relation} ${o.label}" }
        "- ${n.label} [${n.type}]" + (if (n.notes.isNotBlank()) ": ${n.notes.take(120)}" else "") +
            (if (rel.isNotBlank()) " ($rel)" else "")
    }
}
