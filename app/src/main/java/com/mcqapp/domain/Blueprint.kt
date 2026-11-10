package com.mcqapp.domain

data class BlueprintSection(
    val categoryId: String,
    val title: String = "",
    val parts: Int,
    val children: List<BlueprintSection> = emptyList(),
    val scopeCategoryIds: List<String> = emptyList()
) {
    val isTerminal: Boolean get() = children.isEmpty()
    fun terminals(): List<BlueprintSection> =
        if (isTerminal) listOf(this) else children.flatMap { it.terminals() }
    fun allCategoryIds(): List<String> =
        listOf(categoryId) + children.flatMap { it.allCategoryIds() }
}

data class Blueprint(
    val id: Long,
    val paperId: String,
    val name: String,
    val sections: List<BlueprintSection>,
    val createdAt: Long = 0
) {
    val terminals: List<BlueprintSection> get() = sections.flatMap { it.terminals() }
    val allCategoryIds: List<String> get() = sections.flatMap { it.allCategoryIds() }
}

data class PlanBlueprint(
    val label: String,
    val sections: List<BlueprintSection>
) {
    val categoryIds: List<String>
        get() = sections.flatMap { it.terminals() }.flatMap { it.scopeCategoryIds }.distinct()
    val union: Set<String> get() = categoryIds.toSet()
    val terminals: List<BlueprintSection> get() = sections.flatMap { it.terminals() }
}

data class BlueprintSectionView(
    val categoryId: String,
    val title: String,
    val parts: Int,
    /** Questions this section can draw from: every passage member counts once. */
    val pool: Int,
    val available: Boolean,
    val children: List<BlueprintSectionView> = emptyList()
) { val isTerminal: Boolean get() = children.isEmpty() }

data class BlueprintSectionPreview(
    val view: BlueprintSectionView,
    val requested: Int
)

data class BlueprintSummary(
    val id: Long,
    val name: String,
    val sectionCount: Int
)
