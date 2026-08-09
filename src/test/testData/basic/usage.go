package basic

// Consumer exercises Rect so usage search has something to find.
func Consumer() float64 {
	r := Rect{W: 2, H: 3}
	area := r.Area()
	r.W = 10
	return area + r.Area()
}
