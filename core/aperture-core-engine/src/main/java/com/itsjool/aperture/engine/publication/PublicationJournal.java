package com.itsjool.aperture.engine.publication;

import java.util.List;

public class PublicationJournal {

    public enum State {
        PREPARED,
        COMMITTED
    }

    private State state;
    private List<FileMapping> mappings;

    public static class FileMapping {
        private String target;
        private String staging;
        private String backup;
        private boolean originalTargetExisted;

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getStaging() {
            return staging;
        }

        public void setStaging(String staging) {
            this.staging = staging;
        }

        public String getBackup() {
            return backup;
        }

        public void setBackup(String backup) {
            this.backup = backup;
        }

        public boolean isOriginalTargetExisted() {
            return originalTargetExisted;
        }

        public void setOriginalTargetExisted(boolean originalTargetExisted) {
            this.originalTargetExisted = originalTargetExisted;
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public List<FileMapping> getMappings() {
        return mappings;
    }

    public void setMappings(List<FileMapping> mappings) {
        this.mappings = mappings;
    }
}
