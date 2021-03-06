/*
 * Copyright [2013] [Pascal Lombard]
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.elasticsearch.river.subversion.type;

import com.google.common.collect.Lists;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import org.tmatesoft.svn.core.SVNLogEntry;

import java.util.Date;
import java.util.List;

/**
 * Java class for handling of revisions,
 * meant to map lists of SVNDocuments.
 */
@SuppressWarnings("unused")
public class SubversionRevision {


    List<SubversionDocument> documents;
    @Expose final String author;
    @Expose final String repository;
    @Expose final long revision;
    @Expose final Date date;
    @Expose final String message;

    public static final String TYPE_NAME = "svnrevision";

    private static transient final HashFunction hf = Hashing.md5();
    // TODO : find a (better) workaround with joda-time
    public static final String DATE_TIME_ISO8601_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    public SubversionRevision(SVNLogEntry logEntry, String repository) {
        this.author = logEntry.getAuthor();
        this.repository = repository;
        this.revision = logEntry.getRevision();
        this.date = logEntry.getDate();
        this.message = logEntry.getMessage();
        this.documents = Lists.newArrayList();
    }

    public void addDocument(SubversionDocument doc) {
        documents.add(doc);
    }

    public List<SubversionDocument> getDocuments() {
        return documents;
    }

    public String json() {
        Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .setDateFormat(DATE_TIME_ISO8601_FORMAT)
                .create();
        return gson.toJson(this);
    }

    @Override
    public String toString() {
        return json();
    }

    /**
     * Repository@revision should be sufficient to uniquely identify a revision
     * @return  a loosely constructed hashcode converted to String
     */
    public String id() {
        return hf.newHasher()
                .putUnencodedChars(repository)
                .putLong(revision)
                .hash()
                .toString();
    }


}
