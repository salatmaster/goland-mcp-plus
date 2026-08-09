package basic

// double is a trivial function, the kind inlining is meant for.
func double(x int) int { return x * 2 }

// UseDouble calls it twice so inlining has something to rewrite.
func UseDouble() int {
	a := double(2)
	b := double(3)
	return a + b
}
