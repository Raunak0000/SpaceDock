package com.spacedock.dto;

public class DeployRequest {
    private String repoUrl;

    public DeployRequest() {} // for JSON parsing

    public String getRepoUrl(){
        return repoUrl;
    }
    public void setRepoUrl(String repoUrl){
        this.repoUrl = repoUrl;
    }
}
