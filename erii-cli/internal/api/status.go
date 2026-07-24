package api

import (
	"encoding/json"
	"fmt"
)

func (c *Client) GetGroupStatus(botID, groupID string) (*GroupStatus, error) {
	data, err := c.doRequest("GET", fmt.Sprintf("/api/bot/%s/group/%s/status", botID, groupID), nil)
	if err != nil {
		return nil, err
	}
	var status GroupStatus
	if err := json.Unmarshal(data, &status); err != nil {
		return nil, err
	}
	return &status, nil
}
