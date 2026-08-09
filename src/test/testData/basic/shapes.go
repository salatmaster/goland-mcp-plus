package basic

// Shape is something with an area.
type Shape interface {
	Area() float64
	Name() string
}

// Rect satisfies Shape with value receivers.
type Rect struct {
	W float64
	H float64
}

func (r Rect) Area() float64 { return r.W * r.H }
func (r Rect) Name() string  { return "rect" }

// Circle declares its methods on a pointer receiver, so *Circle satisfies
// Shape but Circle does not. This is the classic Go trap the tools explain.
type Circle struct {
	R float64
}

func (c *Circle) Area() float64 { return 3.14159 * c.R * c.R }
func (c *Circle) Name() string  { return "circle" }

// Triangle is missing Name entirely.
type Triangle struct {
	Base   float64
	Height float64
}

func (t Triangle) Area() float64 { return t.Base * t.Height / 2 }

// Namer is a single-method interface that several shapes also satisfy.
type Namer interface {
	Name() string
}

// Sizer is satisfied by nothing in this fixture.
type Sizer interface {
	Size() int
}
