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

package org.elasticsearch.river.subversion.crawler;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.river.subversion.type.SubversionDocument;
import org.elasticsearch.river.subversion.type.SubversionRevision;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.io.ISVNSession;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Container for SVN repository browsing
 */
public class SubversionCrawler {

    private static ESLogger logger = Loggers.getLogger(SubversionCrawler.class);

    // Setup factories to use every protocol :
    // svn://, svn+xxx://	SVNRepositoryFactoryImpl (org.tmatesoft.svn.core.internal.io.svn)
    // http://, https://	DAVRepositoryFactory (org.tmatesoft.svn.core.internal.io.dav)
    // file:/// (FSFS only)	FSRepositoryFactory (org.tmatesoft.svn.core.internal.io.fs)
    static {
        FSRepositoryFactory.setup();
        SVNRepositoryFactoryImpl.setup();
        DAVRepositoryFactory.setup();
    }

    /**
     * Return the latest revision of a SVN directory
     *
     * @param reposAsURL URL to the repository
     * @param parameters (login, password, path)
     * @return latest revision
     * @throws SVNException
     */
    public static long getLatestRevision(URL reposAsURL, Parameters parameters)
            throws SVNException, URISyntaxException {
        SVNURL svnUrl;
        SVNRepository repository;
        if(reposAsURL.getProtocol().equalsIgnoreCase("file")) {
            svnUrl = SVNURL.fromFile(new File(reposAsURL.toURI()));
            repository = SVNRepositoryFactory.create(svnUrl);
        } else {
            svnUrl = SVNURL.create(
                    reposAsURL.getProtocol(),
                    "",
                    reposAsURL.getHost(),
                    reposAsURL.getPort(),
                    reposAsURL.getPath(),
                    false
            );
            repository = SVNRepositoryFactory.create(svnUrl);
            ISVNAuthenticationManager authManager =
                    SVNWCUtil.createDefaultAuthenticationManager(
                            parameters.getLogin().get(),
                            parameters.getPassword().get());
            repository.setAuthenticationManager( authManager );
        }

        logger.debug("Repository Root: {}", repository.getRepositoryRoot(true));
        logger.debug("Repository UUID: {}", repository.getRepositoryUUID(true));
        logger.debug("Repository HEAD Revision: {}", repository.getLatestRevision());

        // call getDir() at HEAD revision,
        // no commit messages or entries necessary
        return repository.getDir(parameters.getPath().get(), -1, false, null).getRevision();
    }

    public static List<SubversionRevision> getRevisions(URL reposAsURL,
                                                        Parameters parameters)
            throws SVNException, URISyntaxException {
        List<SubversionRevision> result = Lists.newArrayList();
        // Init the first revision to get
        Long start = parameters.getStartRevision().get();
        String path = parameters.getPath().get();
        String login = parameters.getLogin().get();
        String password = parameters.getPassword().get();
        // Init the last revision to get
        // (but first, init the repos)
        SVNURL svnUrl;
        SVNRepository repository;
        if(reposAsURL.getProtocol().equalsIgnoreCase("file")) {
            svnUrl = SVNURL.fromFile(new File(reposAsURL.toURI()));
            repository = SVNRepositoryFactory.create(svnUrl, ISVNSession.KEEP_ALIVE);
        } else {
            svnUrl = SVNURL.create(
                    reposAsURL.getProtocol(),
                    "",
                    reposAsURL.getHost(),
                    reposAsURL.getPort(),
                    reposAsURL.getPath(),
                    false
            );
            repository = SVNRepositoryFactory.create(svnUrl);
            ISVNAuthenticationManager authManager = SVNWCUtil
                    .createDefaultAuthenticationManager(login, password);
            repository.setAuthenticationManager( authManager );
        }

        // Attempt to deal with the non-exitence of the path at the specified revision
        Long end = sanitizeEndRevision(repository, path, parameters);
        if (end < 0) {
            // The path likely didn't exist at any revision in the range
            logger.warn("Path [{}] likely didn't exist between revision [{}] to [{}]",
                    path, parameters.getStartRevision().get(), parameters.getEndRevision().get());
            return result;
        }
        logger.info("Retrieving revisions of {}{} from [{}] to [{}]",
                reposAsURL, path, start, end);

        String[] targetPaths = new String[1];
        targetPaths[0] = path;

        // Do a "svn log" for revisions in the range
        Collection logEntries =
                repository.log(
                        targetPaths,
                        null,
                        start,
                        end,
                        true,
                        true
                );
        for (Object logEntryObject : logEntries) {
            SVNLogEntry logEntry = (SVNLogEntry) logEntryObject;
            // Map the obtained logEntry to the jsonable/indexable class
            SubversionRevision subversionRevision =
                    new SubversionRevision(logEntry, repository.getLocation().getPath());

            Map<String, SVNLogEntryPath> changedPaths = logEntry.getChangedPaths();

            for (Map.Entry<String, SVNLogEntryPath> entry : changedPaths.entrySet()) {
                // For each changed path, get the corresponding SVNDocument
                SVNLogEntryPath svnLogEntryPath = entry.getValue();
                logger.debug("Extracting entry [{}]", entry.getKey());
                // Check if it's not to be filtered
                LogEntryFilter toFilter = checkLogEntryPath(parameters,
                        repository, logEntry.getRevision(), svnLogEntryPath);

                // Add the doc, unless instructed not to.
                if( !toFilter.crawlingToBePrevented() ) {
                    subversionRevision.addDocument(
                            new SubversionDocument(
                                    svnLogEntryPath,
                                    repository,
                                    logEntry.getRevision(),
                                    subversionRevision,
                                    toFilter
                            )
                    );
                }
            }
            result.add(subversionRevision);
        }
        logger.info("Retrieved revisions of {}{} from [{}] to [{}] : [{}] revisions",
                reposAsURL, path, start, end, result.size());
        return result;
    }

    /**
     * Attempt to get a sensible end revision, ie. the youngest revision in the range
     * where the path exists
     * @param repository the repository initialized before
     * @param path the path to look for
     * @param parameters the parameters for start/end revisions
     * @return a sensible end revision, or an invalid (<0) one if none found
     * @throws SVNException
     */
    private static Long sanitizeEndRevision(SVNRepository repository,
                                            String path,
                                            Parameters parameters)
            throws SVNException {
        Long result = parameters.getEndRevision().isPresent() ?
                parameters.getEndRevision().get() // end crawl at end revision...
                : repository.getLatestRevision();
        // If path is the repository root, exit immediately, no need to test further
        if ("/".equalsIgnoreCase(path)) {
            return result;
        }
        // Ensure that the path exists at this end revision,
        // reducing the range to index if necessary.
        SVNDirEntry entry = repository.info(path, result);
        // If entry is not null, it's okay, we have confirmation that
        // the path exists at the specified revision, let's roll
        if (entry != null) {
            return result;
        } else {
            // If entry is null, there are 2 possibilities
            // 1. The path has been deleted at one point in the revision range
            // 2. The path doesn't even exist in the revision range
            // Extracting the history of the path would then mean
            // extracting the history to the last revision where it existed
            return getLastValidRevision(repository,
                    path,
                    parameters.getStartRevision().get(),
                    result);
        }
    }

    /**
     * Try to find the last valid revision, ie. the younger revision where the path is garanteed
     * to have existed
     *
     * @param repository the repository initialized before
     * @param path the path to look for
     * @param startRevision  the oldest revision
     * @param endRevision  the youngest revision
     * @return a revision where the path exists, or an invalid one (<0) if none found
     * @throws SVNException
     */
    private static Long getLastValidRevision(SVNRepository repository,
                                             String path,
                                             Long startRevision,
                                             Long endRevision)
            throws SVNException {
        Long result = -1L;
        Long revision = endRevision;
        if (startRevision >= endRevision) {
            logger.error("Start Revision [{}] must be older than End Revision [{}]",
                    startRevision, endRevision);
            return result;
        }
        // Iterate backward through the revisions to find one when the path existed
        while (revision >= startRevision) {
            SVNDirEntry entry = repository.info(path, revision);
            if (entry != null) {
                result = revision;
                break;
            }
            revision--;
        }
        return result;
    }

    /** Check the entry with the different parameters tests passed to the crawler.
     *
     * @param parameters the parameters passed to the crawler
     * @param repository the repository initialized before
     * @param revision the revision to consider
     * @param svnLogEntryPath the entry to test
     * @return LogEntryFilter whether or not, and how, the entry is to be filtered out
     * @throws SVNException
     */
    private static LogEntryFilter checkLogEntryPath(Parameters parameters,
                                             SVNRepository repository,
                                             Long revision,
                                             SVNLogEntryPath svnLogEntryPath)
            throws SVNException {
        LogEntryFilter result;
        // Check the patterns
        for(Pattern pattern:parameters.getPatternsToFilter()) {
            Matcher matcher = pattern.matcher(svnLogEntryPath.getPath());
            if( matcher.matches() ) {
                result = new LogEntryFilter(true,true,"matches ["+pattern.toString()+"]");
                logger.warn("Entry [{}] filtered out : [{}]",
                        svnLogEntryPath.getPath(),
                        result.getReason().get());
                return result;
            }
        }
        // Check the file size
        if(parameters.getMaximumFileSize().isPresent()) {
            if (svnLogEntryPath.getType() == 'A'
                    || svnLogEntryPath.getType() == 'M') {
                SVNDirEntry dirEntry = repository.info(
                        svnLogEntryPath.getPath(),
                        revision
                );
                if( dirEntry.getSize() > parameters.getMaximumFileSize().get() ) {
                    result = new LogEntryFilter(true,false,"size too big ["+dirEntry.getSize()+"]");
                    logger.warn("Entry [{}] filtered out : [{}] ",
                            svnLogEntryPath.getPath(),
                            result.getReason().get());
                    return result;
                }
            }
        }
        return new LogEntryFilter(false, false, null);
    }

    /**
     * Get the SVNEntry file content
     *
     * @param entry      the SVNEntry
     * @param repository the repository containing the entry
     * @return the text content of the file, or null if exception or not a file
     */
    // TODO: Sanitize this method, properly escape the content, check on encoding, visibility...
    public static String getContent(SVNDirEntry entry, SVNRepository repository) {
        String content;
        // Only applies to files
        if (entry.getKind() != SVNNodeKind.FILE) {
            return null;
        }

        // A terrible way to find the entry path relative to the repository root
        String path = entry.getURL().toString().replaceFirst(
                entry.getRepositoryRoot().toString(),
                "");

        SVNProperties fileProperties = new SVNProperties();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            SVNNodeKind kind = repository.checkPath(path, entry.getRevision());
            // if the kind is "none", file simply does not exist
            if (!kind.equals(SVNNodeKind.FILE)) {
                return null;
            }
            repository.getFile(path, entry.getRevision(), fileProperties, outputStream);
            String mimeType = fileProperties.getStringValue(SVNProperty.MIME_TYPE);
            boolean isTextType = SVNProperty.isTextMimeType(mimeType);
            if (isTextType) {
                content = outputStream.toString(Charsets.UTF_8.name());
            } else {
                content = "Not text type";
            }

        } catch (SVNException e) {
            e.printStackTrace();
            return null;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }

        return content;
    }

}
