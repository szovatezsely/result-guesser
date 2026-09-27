// Shared between the app bar (which hosts the "Újraszámolás" button) and the
// match page (which runs the calculation).
import { ref } from 'vue'

/** Incremented by the app bar button; the match page recalculates when it changes. */
export const recalcRequests = ref(0)

/** True while the match page is loading or recalculating (disables the button). */
export const calculating = ref(false)
