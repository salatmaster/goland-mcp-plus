package billing

// Spec is declared here, so this package writes it bare and any other package
// that mentions it has to qualify it.
type Spec struct {
	ID int64
}

// Client is the real implementation the interface is written for.
type Client struct{}

func (c *Client) Cancel(id int64, spec Spec) error { return nil }
