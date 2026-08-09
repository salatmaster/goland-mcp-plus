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
