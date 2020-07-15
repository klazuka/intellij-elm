package org.elm.lang.core.psi.elements

sealed class Pipeline {
    abstract val pipeline: ElmBinOpExpr
    abstract fun isNonNormalizedRightPipeline(): Boolean

    data class LeftPipeline(override val pipeline: ElmBinOpExpr) : Pipeline() {
        override fun isNonNormalizedRightPipeline(): Boolean {
            return false
        }
    }

    data class RightPipeline(override val pipeline: ElmBinOpExpr) : Pipeline() {
        override fun isNonNormalizedRightPipeline(): Boolean {
            return run {
                val firstPart =
                        pipeline
                                .parts
                                .firstOrNull()
                if (firstPart is ElmFunctionCallExpr) {
                    firstPart.arguments.count() > 0
                } else {
                    false
                }
            }
        }
    }

}