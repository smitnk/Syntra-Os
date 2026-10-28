package com.syntra.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntra.os.AppViewModel
import com.syntra.os.memory.Graph
import com.syntra.os.memory.Node
import kotlin.math.sqrt
import kotlin.random.Random

/** Force-directed layout state kept outside Compose state; `tick` triggers redraws. */
private class Layout {
    val pos = HashMap<String, Offset>()
    val vel = HashMap<String, Offset>()

    fun sync(g: Graph) {
        val ids = g.nodes.map { it.id }.toSet()
        pos.keys.retainAll(ids); vel.keys.retainAll(ids)
        g.nodes.forEach { n ->
            if (n.id !in pos) {
                val anchor = g.neighbors(n.id).firstNotNullOfOrNull { pos[it.second.id] } ?: Offset.Zero
                pos[n.id] = anchor + Offset(Random.nextFloat() * 120 - 60, Random.nextFloat() * 120 - 60)
                vel[n.id] = Offset.Zero
            }
        }
    }

    /** One simulation step. O(n²) repulsion. shortcut: fine to ~600 nodes; use Barnes–Hut beyond that. */
    fun step(g: Graph, alpha: Float) {
        val ids = pos.keys.toList()
        val force = HashMap<String, Offset>(ids.size)
        for (i in ids.indices) {
            var f = -pos[ids[i]]!! * 0.008f // gravity to centre
            val p = pos[ids[i]]!!
            for (j in ids.indices) if (i != j) {
                val d = p - pos[ids[j]]!!
                val d2 = (d.x * d.x + d.y * d.y).coerceAtLeast(25f)
                f += d * (2200f / d2 / sqrt(d2))
            }
            force[ids[i]] = f
        }
        for (e in g.edges) {
            val a = pos[e.from] ?: continue
            val b = pos[e.to] ?: continue
            val d = b - a
            val len = d.getDistance().coerceAtLeast(1f)
            val pull = d * ((len - 70f) / len * 0.05f)
            force[e.from] = force[e.from]!! + pull
            force[e.to] = force[e.to]!! - pull
        }
        for (id in ids) {
            var v = (vel[id]!! + force[id]!! * alpha) * 0.82f
            val s = v.getDistance()
            if (s > 30f) v = v * (30f / s)
            vel[id] = v
            pos[id] = pos[id]!! + v
        }
    }
}

@Composable
fun MemoryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val g = vm.graph
    val layout = remember { Layout() }
    var tick by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var focusCommunity by remember { mutableStateOf<Int?>(null) }

    val comm = remember(g) { g.communities() }
    val degree = remember(g) { g.edges.flatMap { listOf(it.from, it.to) }.groupingBy { it }.eachCount() }
    val legend = remember(g) {
        comm.entries.groupBy({ it.value }, { it.key }).entries.sortedByDescending { it.value.size }.take(12).map { (c, ids) ->
            val hub = ids.maxBy { degree[it] ?: 0 }
            Triple(c, g.nodes.first { it.id == hub }.label, ids.size)
        }
    }
    val hits = remember(g, query) { g.search(query).map { it.id }.toSet() }

    LaunchedEffect(g) {
        layout.sync(g)
        var alpha = 1f
        while (alpha > 0.02f) { withFrameNanos { }; layout.step(g, alpha); alpha *= 0.99f; tick++ }
    }

    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE } }

    Box(modifier.fillMaxSize().background(Bg)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, zoom, _ -> scale = (scale * zoom).coerceIn(0.2f, 6f); pan += p }
                }
                .pointerInput(g) {
                    detectTapGestures { tap ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val world = (tap - c - pan) / scale
                        selected = layout.pos.minByOrNull { (it.value - world).getDistance() }
                            ?.takeIf { (it.value - world).getDistance() * scale < 28.dp.toPx() }?.key
                    }
                },
        ) {
            tick // read so the canvas redraws every simulation frame
            val c = center + pan
            fun scr(id: String) = layout.pos[id]?.let { c + it * scale }
            val sel = selected
            val nbr = sel?.let { s -> g.neighbors(s).map { it.second.id }.toSet() }.orEmpty()
            fun visible(id: String) = (focusCommunity == null || comm[id] == focusCommunity) && (hits.isEmpty() || id in hits || id == sel)

            for (e in g.edges) {
                val a = scr(e.from) ?: continue
                val b = scr(e.to) ?: continue
                val lit = sel != null && (e.from == sel || e.to == sel)
                val base = Palette[(comm[e.from] ?: 0) % Palette.size]
                val alphaE = if (lit) 0.9f else if (visible(e.from) && visible(e.to)) 0.25f else 0.04f
                drawLine(if (lit) Cyan else base.copy(alpha = alphaE), a, b, if (lit) 2.5f else 1.2f)
            }
            labelPaint.textSize = 11.sp.toPx()
            for (n in g.nodes) {
                val p = scr(n.id) ?: continue
                val col = Palette[(comm[n.id] ?: 0) % Palette.size]
                val r = (3f + sqrt((degree[n.id] ?: 0).toFloat()) * 1.8f).dp.toPx() * scale.coerceIn(0.6f, 1.8f)
                val vis = visible(n.id)
                drawCircle(col.copy(alpha = if (vis) 0.95f else 0.12f), r, p)
                if (n.id == sel) drawCircle(Cyan, r + 5f, p, style = Stroke(3f))
                if (n.id in hits) drawCircle(Color.White, r + 3f, p, style = Stroke(2f))
                val showLabel = n.id == sel || n.id in nbr || n.id in hits || (vis && (scale > 1.4f || (degree[n.id] ?: 0) >= 4 || g.nodes.size < 40))
                if (showLabel) {
                    labelPaint.alpha = if (vis) 230 else 60
                    drawContext.canvas.nativeCanvas.drawText(n.label.take(28), p.x + r + 4f, p.y + 4f, labelPaint)
                }
            }
        }

        // Search + stats + communities
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search nodes…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "Clear") } },
                shape = RoundedCornerShape(12.dp))
            Text("${g.nodes.size} nodes · ${g.edges.size} edges · ${legend.size} communities", color = Dim, fontSize = 11.sp,
                modifier = Modifier.padding(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                legend.forEach { (cid, name, count) ->
                    val on = focusCommunity == cid
                    Row(Modifier.background(Panel, RoundedCornerShape(16.dp))
                        .border(1.dp, if (on) Cyan else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { focusCommunity = if (on) null else cid }.padding(10.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Palette[cid % Palette.size], CircleShape))
                        Spacer(Modifier.size(6.dp))
                        Text("$name  $count", fontSize = 12.sp)
                    }
                }
            }
        }

        if (g.nodes.isEmpty()) Text("Memory is empty. Chat with Syntra or tap + to add a node.", color = Dim,
            modifier = Modifier.align(Alignment.Center).padding(32.dp))

        val node = selected?.let { g.find(it) }
        if (node != null) NodePanel(vm, node, onSelect = { selected = it }, onClose = { selected = null }, Modifier.align(Alignment.BottomCenter))
        else FloatingActionButton({ adding = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Outlined.Add, "Add node") }
    }

    if (adding) {
        var label by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("concept") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("New memory node") },
            text = { Column { OutlinedTextField(label, { label = it }, label = { Text("Label") }); OutlinedTextField(type, { type = it }, label = { Text("Type") }) } },
            confirmButton = { TextButton({ if (label.isNotBlank()) { val (ng, n) = g.upsert(label, type, ""); vm.updateGraph(ng); selected = n.id }; adding = false }) { Text("Add") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}

@Composable
private fun NodePanel(vm: AppViewModel, node: Node, onSelect: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var label by remember(node.id) { mutableStateOf(node.label) }
    var type by remember(node.id) { mutableStateOf(node.type) }
    var notes by remember(node.id) { mutableStateOf(node.notes) }
    var target by remember(node.id) { mutableStateOf("") }
    var relation by remember(node.id) { mutableStateOf("related_to") }
    val g = vm.graph

    Card(modifier.fillMaxWidth().padding(8.dp).heightIn(max = 420.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NODE INFO", color = Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton({ vm.updateGraph(g.remove(node.id)); onClose() }) { Icon(Icons.Outlined.Delete, "Delete node", tint = Danger) }
                IconButton(onClose) { Icon(Icons.Outlined.Close, "Close") }
            }
            OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Label") }, singleLine = true)
            OutlinedTextField(type, { type = it }, Modifier.fillMaxWidth(), label = { Text("Type") }, singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, maxLines = 5)
            TextButton({ vm.updateGraph(g.update(node.copy(label = label.trim().ifBlank { node.label }, type = type, notes = notes))) }) { Text("Save changes") }

            val links = g.neighbors(node.id)
            if (links.isNotEmpty()) Text("Connections (${links.size})", color = Dim, fontSize = 12.sp)
            links.forEach { (e, o) ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(o.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (e.from == node.id) "→" else "←"} ${e.relation}", color = Violet, fontSize = 12.sp, modifier = Modifier.weight(0.45f))
                    Text(o.label, fontSize = 14.sp, modifier = Modifier.weight(0.55f))
                    IconButton({ vm.updateGraph(g.copy(edges = g.edges - e)) }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Remove link", tint = Dim) }
                }
            }
            Text("Link to", color = Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(relation, { relation = it }, Modifier.weight(1f), label = { Text("Relation") }, singleLine = true)
                OutlinedTextField(target, { target = it }, Modifier.weight(1f), label = { Text("Node") }, singleLine = true)
            }
            TextButton({
                if (target.isNotBlank()) {
                    val (ng, t) = g.upsert(target, "concept", "")
                    vm.updateGraph(ng.link(node.id, t.id, relation.ifBlank { "related_to" })); target = ""
                }
            }) { Text("Add link") }
        }
    }
}
