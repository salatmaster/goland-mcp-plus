package problems

import (
	"os"
	"fmt"
	str "strings"
)

// Sorted wrong on purpose, and the alias repeats the package name: both are
// inspection problems that carry a fix, unlike a type error, which does not.
func Greet(name string) string {
	return fmt.Sprintf("hello %s%s", str.TrimSpace(name), os.Getenv("SUFFIX"))
}
