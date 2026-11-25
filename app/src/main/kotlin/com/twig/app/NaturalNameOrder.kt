package com.twig.app

/** Compares lowercase names, treating consecutive ASCII digits as one number. */
internal object NaturalNameOrder : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        var paddingOrder = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            if (leftChar !in '0'..'9' || rightChar !in '0'..'9') {
                if (leftChar != rightChar) return leftChar - rightChar
                leftIndex++
                rightIndex++
                continue
            }

            var leftStart = leftIndex
            var rightStart = rightIndex
            while (leftStart < left.length && left[leftStart] == '0') leftStart++
            while (rightStart < right.length && right[rightStart] == '0') rightStart++
            var leftEnd = leftStart
            var rightEnd = rightStart
            while (leftEnd < left.length && left[leftEnd] in '0'..'9') leftEnd++
            while (rightEnd < right.length && right[rightEnd] in '0'..'9') rightEnd++

            // Compare significant digit counts first so the numbers can be arbitrarily long.
            val lengthOrder = (leftEnd - leftStart) - (rightEnd - rightStart)
            if (lengthOrder != 0) return lengthOrder
            // Padding breaks ties only after the rest of the name has compared equal.
            if (paddingOrder == 0) {
                paddingOrder = (leftStart - leftIndex) - (rightStart - rightIndex)
            }
            while (leftStart < leftEnd) {
                val digitOrder = left[leftStart++] - right[rightStart++]
                if (digitOrder != 0) return digitOrder
            }
            leftIndex = leftEnd
            rightIndex = rightEnd
        }
        val remainingOrder = (left.length - leftIndex) - (right.length - rightIndex)
        return if (remainingOrder != 0) remainingOrder else paddingOrder
    }
}
