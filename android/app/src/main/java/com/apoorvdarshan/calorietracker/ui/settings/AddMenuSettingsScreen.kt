package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AddMenuConfig
import com.apoorvdarshan.calorietracker.models.AddMenuGroupConfig
import com.apoorvdarshan.calorietracker.models.FoodLogMethod
import com.apoorvdarshan.calorietracker.models.defaultGroupNameRes
import com.apoorvdarshan.calorietracker.models.displayName
import androidx.compose.material3.HorizontalDivider
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

@Composable
fun AddMenuSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val ui by vm.ui.collectAsState()
    var draft by remember(ui.addMenuConfig) { mutableStateOf(ui.addMenuConfig) }
    var addMethodGroupIndex by remember { mutableIntStateOf(-1) }

    fun persist(updated: AddMenuConfig) {
        draft = updated.sanitized()
        vm.setAddMenuConfig(draft)
    }

    val hiddenMethods = remember(draft) {
        val visible = if (draft.usesFlatLayout) {
            draft.resolvedFlatMethods().toSet()
        } else {
            draft.resolvedGroups().flatMap { it.methods }.toSet()
        }
        FoodLogMethod.AddMenuCases.filterNot { it in visible }
    }

    androidx.compose.material3.Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 14.dp,
                bottom = BottomNavScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.nav_settings),
                        color = AppColors.Calorie,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_add_menu_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            item {
                AddMenuSectionCard(title = stringResource(R.string.settings_add_menu_food_section)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_add_menu_groups))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Remove,
                                contentDescription = stringResource(R.string.action_remove),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = draft.groups.size > 0) {
                                        val nextCount = (draft.groups.size - 1).coerceAtLeast(0)
                                        persist(updateGroupCount(draft, nextCount))
                                    }
                                    .padding(4.dp),
                                tint = AppColors.Calorie
                            )
                            Text(
                                draft.groups.size.toString(),
                                modifier = Modifier.padding(horizontal = 12.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.cd_add_food),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = draft.groups.size < 3) {
                                        persist(updateGroupCount(draft, draft.groups.size + 1))
                                    }
                                    .padding(4.dp),
                                tint = AppColors.Calorie
                            )
                        }
                    }
                }
            }

            if (draft.usesFlatLayout) {
                item {
                    FlatMethodsEditor(
                        methods = draft.resolvedFlatMethods(),
                        hiddenMethods = hiddenMethods,
                        onReorder = { from, to ->
                            val methods = draft.resolvedFlatMethods().toMutableList()
                            if (from in methods.indices && to in methods.indices) {
                                val item = methods.removeAt(from)
                                methods.add(to, item)
                                persist(
                                    draft.copy(
                                        flatMethods = methods.map { it.storageKey }
                                    )
                                )
                            }
                        },
                        onRemove = { method ->
                            persist(
                                draft.copy(
                                    flatMethods = draft.flatMethods.filterNot { it == method.storageKey }
                                )
                            )
                        },
                        onAdd = { method ->
                            persist(
                                draft.copy(
                                    flatMethods = draft.flatMethods + method.storageKey
                                )
                            )
                        }
                    )
                }
            } else {
                itemsIndexed(draft.groups) { index, group ->
                    GroupEditor(
                        groupIndex = index,
                        group = group,
                        groupCount = draft.groups.size,
                        hiddenMethods = hiddenMethods,
                        onMoveGroup = { direction ->
                            val groups = draft.groups.toMutableList()
                            val target = index + direction
                            if (index in groups.indices && target in groups.indices) {
                                val item = groups.removeAt(index)
                                groups.add(target, item)
                                persist(draft.copy(groups = groups))
                            }
                        },
                        onNameChange = { name ->
                            val groups = draft.groups.toMutableList()
                            groups[index] = groups[index].copy(name = name)
                            persist(draft.copy(groups = groups))
                        },
                        onMoveMethod = { methodIndex, direction ->
                            val groups = draft.groups.toMutableList()
                            val methods = groups[index].methods.toMutableList()
                            val target = methodIndex + direction
                            if (methodIndex in methods.indices && target in methods.indices) {
                                val item = methods.removeAt(methodIndex)
                                methods.add(target, item)
                                groups[index] = groups[index].copy(methods = methods)
                                persist(draft.copy(groups = groups))
                            }
                        },
                        onRemoveMethod = { method ->
                            val groups = draft.groups.toMutableList()
                            groups[index] = groups[index].copy(
                                methods = groups[index].methods.filterNot { it == method.storageKey }
                            )
                            persist(draft.copy(groups = groups))
                        },
                        onAddMethodClick = { addMethodGroupIndex = index }
                    )
                }
            }

            if (hiddenMethods.isNotEmpty()) {
                item {
                    AddMenuSectionCard(title = stringResource(R.string.settings_add_menu_hidden)) {
                        hiddenMethods.forEachIndexed { idx, method ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(method.icon, contentDescription = null, tint = AppColors.Calorie)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(method.titleRes), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                            }
                            if (idx < hiddenMethods.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = { vm.resetAddMenuConfig() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.settings_add_menu_reset),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.settings_add_menu_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    if (addMethodGroupIndex >= 0) {
        val groupIndex = addMethodGroupIndex
        DropdownMenu(
            expanded = true,
            onDismissRequest = { addMethodGroupIndex = -1 }
        ) {
            hiddenMethods.forEach { method ->
                DropdownMenuItem(
                    text = { Text(stringResource(method.titleRes)) },
                    onClick = {
                        val groups = draft.groups.toMutableList()
                        val cleaned = groups.mapIndexed { idx, group ->
                            if (idx == groupIndex) {
                                group.copy(methods = group.methods + method.storageKey)
                            } else {
                                group.copy(methods = group.methods.filterNot { it == method.storageKey })
                            }
                        }
                        persist(
                            draft.copy(
                                groups = cleaned,
                                flatMethods = draft.flatMethods.filterNot { it == method.storageKey }
                            )
                        )
                        addMethodGroupIndex = -1
                    }
                )
            }
        }
    }
}

@Composable
private fun FlatMethodsEditor(
    methods: List<FoodLogMethod>,
    hiddenMethods: List<FoodLogMethod>,
    onReorder: (Int, Int) -> Unit,
    onRemove: (FoodLogMethod) -> Unit,
    onAdd: (FoodLogMethod) -> Unit
) {
    AddMenuSectionCard(title = stringResource(R.string.settings_add_menu_flat)) {
        methods.forEachIndexed { index, method ->
            MethodRow(
                label = stringResource(method.titleRes),
                icon = method.icon,
                canMoveUp = index > 0,
                canMoveDown = index < methods.lastIndex,
                onMoveUp = { onReorder(index, index - 1) },
                onMoveDown = { onReorder(index, index + 1) },
                onRemove = { onRemove(method) }
            )
            if (index < methods.lastIndex) HorizontalDivider()
        }
        if (hiddenMethods.isNotEmpty()) {
            HorizontalDivider()
            TextButton(onClick = { hiddenMethods.firstOrNull()?.let(onAdd) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_add_menu_add_method))
            }
        }
    }
}

@Composable
private fun GroupEditor(
    groupIndex: Int,
    group: AddMenuGroupConfig,
    groupCount: Int,
    hiddenMethods: List<FoodLogMethod>,
    onMoveGroup: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onMoveMethod: (Int, Int) -> Unit,
    onRemoveMethod: (FoodLogMethod) -> Unit,
    onAddMethodClick: () -> Unit
) {
    val methods = group.methods.mapNotNull(FoodLogMethod::fromStorage)
    AddMenuSectionCard(title = group.displayName()) {
        if (groupCount > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (groupIndex > 0) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onMoveGroup(-1) }
                            .padding(4.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (groupIndex < groupCount - 1) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onMoveGroup(1) }
                            .padding(4.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        val isDefaultName = defaultGroupNameRes(group.name) != null
        OutlinedTextField(
            value = if (isDefaultName) "" else group.name,
            onValueChange = onNameChange,
            placeholder = { Text(group.displayName()) },
            label = { Text(stringResource(R.string.settings_add_menu_group_name)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )
        methods.forEachIndexed { index, method ->
            MethodRow(
                label = stringResource(method.titleRes),
                icon = method.icon,
                canMoveUp = index > 0,
                canMoveDown = index < methods.lastIndex,
                onMoveUp = { onMoveMethod(index, -1) },
                onMoveDown = { onMoveMethod(index, 1) },
                onRemove = { onRemoveMethod(method) }
            )
            if (index < methods.lastIndex) HorizontalDivider()
        }
        if (hiddenMethods.isNotEmpty()) {
            HorizontalDivider()
            TextButton(onClick = onAddMethodClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_add_menu_add_method))
            }
        }
    }
}

@Composable
private fun MethodRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (canMoveUp) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMoveUp)
                    .padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canMoveDown) {
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMoveDown)
                    .padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.action_remove),
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRemove)
                .padding(4.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun AddMenuSectionCard(title: String? = null, content: @Composable () -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
        }
        FudGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            padding = 0.dp
        ) {
            Column(Modifier.padding(vertical = 2.dp)) { content() }
        }
    }
}

private fun updateGroupCount(config: AddMenuConfig, count: Int): AddMenuConfig {
    if (count == 0) {
        val visible = if (config.groups.isEmpty()) {
            config.resolvedFlatMethods()
        } else {
            config.resolvedGroups().flatMap { it.methods }
        }
        return config.copy(
            groups = emptyList(),
            flatMethods = visible.map { it.storageKey }
        )
    }

    var groups = config.groups.toMutableList()
    if (groups.isEmpty()) {
        val flat = config.resolvedFlatMethods()
        groups = if (flat.isEmpty()) {
            AddMenuConfig.Default.groups.toMutableList()
        } else {
            mutableListOf(
                AddMenuGroupConfig(
                    name = AddMenuConfig.DEFAULT_GROUP_FALLBACK_NAME,
                    methods = flat.map { it.storageKey }
                )
            )
        }
    }
    while (groups.size < count) {
        groups += AddMenuGroupConfig(
            name = AddMenuConfig.DEFAULT_GROUP_FALLBACK_NAME,
            methods = emptyList()
        )
    }
    while (groups.size > count) {
        groups.removeAt(groups.lastIndex)
    }
    return config.copy(groups = groups, flatMethods = emptyList())
}
