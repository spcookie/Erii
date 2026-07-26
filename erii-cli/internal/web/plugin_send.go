package web

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net/http"

	"erii-cli/internal/api"
)

func pluginSendHandler(token, eriiDir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", http.MethodPost)
			http.Error(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
			return
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Erii-Token")), []byte(token)) != 1 {
			http.Error(w, http.StatusText(http.StatusUnauthorized), http.StatusUnauthorized)
			return
		}

		var request api.PluginCliSendRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 64*1024)).Decode(&request); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
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

		result, err := client.SendPluginCli(request.Input)
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

func pluginProxyErrorStatus(backendStatus int) int {
	if backendStatus >= 400 && backendStatus <= 599 {
		return backendStatus
	}
	return http.StatusBadGateway
}
