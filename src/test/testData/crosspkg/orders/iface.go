package orders

import "example.com/crosspkg/billing"

// Service is an unexported-style interface declared at the use site. It must
// qualify billing.Spec, while *billing.Client writes the same type bare.
type Service interface {
	Cancel(id int64, spec billing.Spec) error
}

// Mock sits in a third package position: it also qualifies the type, which is
// why a text comparison used to find it and miss the real client.
type Mock struct{}

func (m *Mock) Cancel(id int64, spec billing.Spec) error { return nil }

// Wrapper gets Cancel by embedding the client, declaring nothing itself. Go
// promotes the method, so *Wrapper satisfies Service too.
type Wrapper struct {
	*billing.Client
}

// Recorder is the other way a type implements without declaring anything: it
// embeds the interface. Every method comes from the embedded field, so no
// method index knows about Recorder at all.
type Recorder struct {
	Service
}
