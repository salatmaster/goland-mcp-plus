package sig

// Double is the simplest possible subject: one parameter, one result.
func Double(x int) int { return x * 2 }

// Pair takes two differently typed parameters so a reorder is observable in
// the rewritten call, not just in the declaration.
func Pair(a int, b string) string { return b }

// Caller exercises both, so every signature change has call sites to rewrite.
func Caller() string {
	n := Double(21)
	return Pair(n, "answer")
}
