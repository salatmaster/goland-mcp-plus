package sig

// Repo is an interface whose implementors should follow a signature change.
type Repo interface {
	Get(id int) string
}

// Memory implements Repo with a value receiver.
type Memory struct{}

func (m Memory) Get(id int) string { return "" }

// UseRepo calls through the interface.
func UseRepo(r Repo) string { return r.Get(1) }
