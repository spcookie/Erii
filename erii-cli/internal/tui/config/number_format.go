package config

import (
	"fmt"
	"strconv"
)

// formatNumberValue renders config numbers as ordinary decimal text. JSON
// numbers are decoded as float64, but integral values should still look like
// integers in the browser and editor.
func formatNumberValue(value any) string {
	switch number := value.(type) {
	case float64:
		return strconv.FormatFloat(number, 'f', -1, 64)
	case float32:
		return strconv.FormatFloat(float64(number), 'f', -1, 32)
	case int:
		return strconv.Itoa(number)
	case int8:
		return strconv.FormatInt(int64(number), 10)
	case int16:
		return strconv.FormatInt(int64(number), 10)
	case int32:
		return strconv.FormatInt(int64(number), 10)
	case int64:
		return strconv.FormatInt(number, 10)
	case uint:
		return strconv.FormatUint(uint64(number), 10)
	case uint8:
		return strconv.FormatUint(uint64(number), 10)
	case uint16:
		return strconv.FormatUint(uint64(number), 10)
	case uint32:
		return strconv.FormatUint(uint64(number), 10)
	case uint64:
		return strconv.FormatUint(number, 10)
	default:
		return fmt.Sprintf("%v", value)
	}
}
