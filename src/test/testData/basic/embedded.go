package basic

// ReadWriter embeds two interfaces; its method set is the union of both.
type ReadWriter interface {
	Reader
	Writer
}

type Reader interface {
	Read(p []byte) (int, error)
}

type Writer interface {
	Write(p []byte) (int, error)
}

// Pipe satisfies ReadWriter through both embedded interfaces.
type Pipe struct{}

func (p Pipe) Read(b []byte) (int, error)  { return 0, nil }
func (p Pipe) Write(b []byte) (int, error) { return 0, nil }

// HalfPipe only reads, so it satisfies Reader but not ReadWriter.
type HalfPipe struct{}

func (h HalfPipe) Read(b []byte) (int, error) { return 0, nil }

// Boxed embeds Rect and declares nothing, so Go promotes Area and Name into it
// and Boxed satisfies Shape.
type Boxed struct {
	Rect
}

// BoxedCircle embeds Circle by value. Circle declares its methods on a pointer
// receiver, so they land in the method set of *BoxedCircle and not of
// BoxedCircle — the pointer-receiver trap, one level of embedding away.
type BoxedCircle struct {
	Circle
}

// PointerBoxed embeds *Circle, which puts those same pointer-receiver methods
// into the method set of the value as well.
type PointerBoxed struct {
	*Circle
}
