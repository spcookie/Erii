package web

import (
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strconv"

	"erii-cli/internal/api"
)

func pluginMatchHandler(token, eriiDir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			http.Error(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
			return
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Erii-Token")), []byte(token)) != 1 {
			http.Error(w, http.StatusText(http.StatusUnauthorized), http.StatusUnauthorized)
			return
		}

		query := r.URL.Query().Get("query")
		limit := 20
		if rawLimit := r.URL.Query().Get("limit"); rawLimit != "" {
			if parsed, err := strconv.Atoi(rawLimit); err == nil {
				limit = parsed
			}
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")

		client, err := api.NewClientFromIPCDir(eriiDir)
		if err != nil {
			w.WriteHeader(http.StatusBadGateway)
			_ = json.NewEncoder(w).Encode(map[string]string{
				"status":  "error",
				"message": err.Error(),
			})
			return
		}

		result, err := client.MatchPluginCommands(query, limit)
		if err != nil {
			statusCode := pluginProxyErrorStatus(0)
			if result != nil {
				statusCode = pluginProxyErrorStatus(result.HTTPStatus)
			}
			w.WriteHeader(statusCode)
			if result != nil {
				_ = json.NewEncoder(w).Encode(result)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]string{
				"status":  "error",
				"message": err.Error(),
			})
			return
		}

		_ = json.NewEncoder(w).Encode(result)
	}
}
