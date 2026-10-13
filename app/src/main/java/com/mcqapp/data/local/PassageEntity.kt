package com.mcqapp.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reading passage that groups several questions (comprehension sets).
 *
 * A passage lives in a category like a question does, so it inherits the
 * paper structure; its member questions keep their own [QuestionEntity.categoryId]
 * and point here through the nullable `passageId` column. Deleting a paper or
 * a category cascades the passage away, exactly as it does for questions;
 * deleting a passage itself is refused while members remain (the
 * repository helper enforces that, since a plain column cannot cascade in
 * reverse).
 */
@Entity(
    tableName = "passages",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class PassageEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val title: String,
    /** The passage body as rich-content elements JSON, like a question's text. */
    val text: String,
    val image: String? = null,
    val sortOrder: Int = 0
)
