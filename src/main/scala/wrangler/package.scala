import scalaz.\/

package object wrangler {
  type Error[T] = Throwable \/ T
}
