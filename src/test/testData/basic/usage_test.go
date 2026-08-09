package basic

import "testing"

func TestArea(t *testing.T) {
	r := Rect{W: 1, H: 1}
	if r.Area() != 1 {
		t.Fatal("bad area")
	}
}
