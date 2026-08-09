package basic

import "time"

// User is a record with struct tags, the detail agents most often get wrong.
type User struct {
	ID        int       `json:"id" db:"user_id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"created_at"`
	secret    string
}

// Audit embeds User, so it carries User's fields too.
type Audit struct {
	User
	Action string `json:"action"`
}

// MaxUsers caps the store.
const MaxUsers = 100

// DefaultName is used when none is given.
var DefaultName = "anonymous"

// NewUser builds a User.
func NewUser(name string) *User { return &User{Name: name} }

// helper is unexported and should stay out of the public API listing.
func helper() {}
