/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.resource.s3.fixtures
import groovy.xml.StreamingMarkupBuilder
import org.gradle.integtests.resource.s3.fixtures.stub.HttpStub
import org.gradle.integtests.resource.s3.fixtures.stub.StubRequest
import org.gradle.test.fixtures.file.TestDirectoryProvider
import org.gradle.test.fixtures.ivy.RemoteIvyRepository
import org.gradle.test.fixtures.server.RepositoryServer
import org.gradle.test.fixtures.server.http.HttpServer
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.tz.FixedDateTimeZone
import org.mortbay.jetty.Request
import org.mortbay.jetty.handler.AbstractHandler

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.security.MessageDigest

class S3Server extends HttpServer implements RepositoryServer {

    public static final String BUCKET_NAME = "tests3bucket"
    private static final DateTimeZone GMT = new FixedDateTimeZone("GMT", "GMT", 0, 0)
    protected static final DateTimeFormatter RCF_822_DATE_FORMAT = DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss z")
            .withLocale(Locale.US)
            .withZone(GMT);

    public static final String ETAG = 'd41d8cd98f00b204e9800998ecf8427e'
    public static final String X_AMZ_REQUEST_ID = '0A398F9A1BAD4027'
    public static final String X_AMZ_ID_2 = 'nwUZ/n/F2/ZFRTZhtzjYe7mcXkxCaRjfrJSWirV50lN7HuvhF60JpphwoiX/sMnh'
    public static final String DATE_HEADER = 'Mon, 29 Sep 2014 11:04:27 GMT'
    public static final String SERVER_AMAZON_S3 = 'AmazonS3'

    TestDirectoryProvider testDirectoryProvider

    S3Server(TestDirectoryProvider testDirectoryProvider) {
        super()
        this.testDirectoryProvider = testDirectoryProvider;
    }

    @Override
    protected void before() {
        start()
    }

    void assertRequest(HttpStub httpStub, HttpServletRequest request) {
        StubRequest stubRequest = httpStub.request
        String path = stubRequest.path
        assert path.startsWith('/')
        assert path == request.pathInfo
        assert stubRequest.method == request.method
        if (stubRequest.body) {
            assert stubRequest.body == request.getInputStream().bytes
        }
        assert stubRequest.params.every {
            request.getParameterMap()[it.key] == it.value
        }
    }

    boolean requestMatches(HttpStub httpStub, HttpServletRequest request) {
        StubRequest stubRequest = httpStub.request
        String path = stubRequest.path
        assert path.startsWith('/')
        boolean result = path == request.pathInfo && stubRequest.method == request.method
        result
    }

    @Override
    RemoteIvyRepository getRemoteIvyRepo() {
        new IvyS3Repository(this, testDirectoryProvider.testDirectory.file("$BUCKET_NAME/ivy"), "/ivy", BUCKET_NAME)
    }

    @Override
    RemoteIvyRepository getRemoteIvyRepo(boolean m2Compatible, String dirPattern) {
        new IvyS3Repository(this, testDirectoryProvider.testDirectory.file("$BUCKET_NAME/ivy"), "/ivy", BUCKET_NAME, m2Compatible, dirPattern)
    }

    @Override
    RemoteIvyRepository getRemoteIvyRepo(boolean m2Compatible, String dirPattern, String ivyFilePattern, String artifactFilePattern) {
        new IvyS3Repository(this, testDirectoryProvider.testDirectory.file("$BUCKET_NAME/ivy"), "/ivy", BUCKET_NAME, m2Compatible, dirPattern, ivyFilePattern, artifactFilePattern)
    }

    @Override
    RemoteIvyRepository getRemoteIvyRepo(String contextPath) {
        new IvyS3Repository(this, testDirectoryProvider.testDirectory.file("$BUCKET_NAME$contextPath"), "$contextPath", BUCKET_NAME)
    }

    @Override
    String getValidCredentials() {
        return """
        credentials(AwsCredentials) {
            accessKey "someKey"
            secretKey "someSecret"
        }"""
    }

    def stubPutFile(File file, String url) {
        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'PUT'
                path = url
                headers = [
                        'Content-Type': 'application/octet-stream',
                        'Connection'  : 'Keep-Alive'
                ]
                body = file.getBytes()

            }
            response {
                status = 200
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        "ETag"            : calculateEtag(file),
                        'Server'          : SERVER_AMAZON_S3
                ]
            }
        }
        expect(httpStub)
    }

    def stubMetaData(File file, String url) {
        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'GET'
                path = url
                headers = [
                        'Content-Type': "application/x-www-form-urlencoded; charset=utf-8",
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = 200
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'ETag'            : calculateEtag(file),
                        'Server'          : SERVER_AMAZON_S3,
                        'Accept-Ranges'   : 'bytes',
                        'Content-Type'    : 'application/octet-stream',
                        'Content-Length'  : "0",
                        'Last-Modified'   : RCF_822_DATE_FORMAT.print(new Date().getTime())
                ]
            }
        }
        expect(httpStub)
    }

    def stubMetaDataBroken(String url) {
        stubMetaDataLightWeightGet(url, 500)
    }

    def stubMetaDataMissing(String url) {
        stubFileNotFound(url)
    }

    private stubMetaDataLightWeightGet(String url, int statusCode) {
        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'GET'
                path = url
                headers = [
                        'Content-Type': "application/x-www-form-urlencoded; charset=utf-8",
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = statusCode
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'Server'          : SERVER_AMAZON_S3,
                        'Content-Type'    : 'application/xml',
                ]
            }
        }
       expect(httpStub)
    }

    def stubGetFile(File file, String url) {
        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'GET'
                path = url
                headers = [
                        'Content-Type': "application/x-www-form-urlencoded; charset=utf-8",
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = 200
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'ETag'            : calculateEtag(file),
                        'Server'          : SERVER_AMAZON_S3,
                        'Accept-Ranges'   : 'bytes',
                        'Content-Type'    : 'application/octet-stream',
                        'Content-Length'  : "${file.length()}",
                        'Last-Modified'   : RCF_822_DATE_FORMAT.print(new Date().getTime())
                ]
                body = file.getBytes()
            }
        }
        expect(httpStub)
    }

    def stubListFile(File file, String bucketName, prefix = 'maven/release/', delimiter = '/') {
        def xml = new StreamingMarkupBuilder().bind {
            ListBucketResult(xmlns: "http://s3.amazonaws.com/doc/2006-03-01/") {
                Name(bucketName)
                Prefix(prefix)
                Marker()
                MaxKeys('1000')
                Delimiter(delimiter)
                IsTruncated('false')
                Contents {
                    Key(prefix)
                    LastModified('2014-09-21T06:44:09.000Z')
                    ETag(ETAG)
                    Size('0')
                    Owner {
                        ID("${(1..57).collect { 'a' }.join()}")
                        DisplayName('me')
                    }
                    StorageClass('STANDARD')
                }
                file.listFiles().each { currentFile ->
                    Contents {
                        Key(prefix + currentFile.name)
                        LastModified('2014-10-01T13:03:29.000Z')
                        ETag(ETAG)
                        Size(currentFile.length())
                        Owner {
                            ID("${(1..57).collect { 'a' }.join()}")
                            DisplayName('me')
                        }
                        StorageClass('STANDARD')
                    }
                    CommonPrefixes {
                        Prefix("${prefix}com/")
                    }
                }
                Contents {
                    Key(prefix + file.name)
                    LastModified('2014-10-01T13:03:29.000Z')
                    ETag(ETAG)
                    Size('19')
                    Owner {
                        ID("${(1..57).collect { 'a' }.join()}")
                        DisplayName('me')
                    }
                    StorageClass('STANDARD')
                }
                CommonPrefixes {
                    Prefix("${prefix}com/")
                }
            }
        }

        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'GET'
                path = "/${bucketName}/"
                headers = [
                        'Content-Type': "application/x-www-form-urlencoded; charset=utf-8",
                        'Connection'  : 'Keep-Alive'
                ]
                params = [
                        'prefix'   : [prefix],
                        'delimiter': [delimiter],
                        'max-keys' : ["1000"]
                ]
            }
            response {
                status = 200
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'Server'          : SERVER_AMAZON_S3,
                        'Content-Type'    : 'application/xml',
                ]
                body = xml.toString()
            }
        }
        expect(httpStub)
    }

    def stubGetFileAuthFailure(String url) {
        def xml = new StreamingMarkupBuilder().bind {
            Error() {
                Code("InvalidAccessKeyId")
                Message("The AWS Access Key Id you provided does not exist in our records.")
                AWSAccessKeyId("notRelevant")
                RequestId("stubbedAuthFailureRequestId")
                HostId("stubbedAuthFailureHostId")
            }
        }

        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'GET'
                path = url
                headers = [
                        'Content-Type': 'application/octet-stream',
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = 403
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'Server'          : SERVER_AMAZON_S3,
                        'Content-Type'    : 'application/xml',
                ]
                body = xml.toString()
            }
        }
        expect(httpStub)
    }


    def stubPutFileAuthFailure(String url) {
        def xml = new StreamingMarkupBuilder().bind {
            Error() {
                Code("InvalidAccessKeyId")
                Message("The AWS Access Key Id you provided does not exist in our records.")
                AWSAccessKeyId("notRelevant")
                RequestId("stubbedAuthFailureRequestId")
                HostId("stubbedAuthFailureHostId")
            }
        }

        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'PUT'
                path = url
                headers = [
                        'Content-Type': 'application/octet-stream',
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = 403
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'Server'          : SERVER_AMAZON_S3,
                        'Content-Type'    : 'application/xml',
                ]
                body = xml.toString()
            }
        }
        expect(httpStub)
    }

    def stubFileNotFound(String url) {
        def xml = new StreamingMarkupBuilder().bind {
            Error() {
                Code("NoSuchKey")
                Message("The specified key does not exist.")
                Key(url)
                RequestId("stubbedRequestId")
                HostId("stubbedHostId")
            }
        }

        HttpStub httpStub = HttpStub.stubInteraction {
            request {
                method = 'GET'
                path = url
                headers = [
                        'Content-Type': 'application/octet-stream',
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = 404
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'Server'          : SERVER_AMAZON_S3,
                        'Content-Type'    : 'application/xml',
                ]
                body = xml.toString()
            }
        }
        expect(httpStub)
    }

    def stubGetFileBroken(String url) {
        HttpStub httpStub = HttpStub.stubInteraction {
            def xml = new StreamingMarkupBuilder().bind {
                Error() {
                    Code("Internal Server Error")
                    Message("Something went seriously wrong")
                    Key(url)
                    RequestId("stubbedRequestId")
                    HostId("stubbedHostId")
                }
            }
            request {
                method = 'GET'
                path = url
                headers = [
                        'Content-Type': 'application/octet-stream',
                        'Connection'  : 'Keep-Alive'
                ]
            }
            response {
                status = 500
                headers = [
                        'x-amz-id-2'      : X_AMZ_ID_2,
                        'x-amz-request-id': X_AMZ_REQUEST_ID,
                        'Date'            : DATE_HEADER,
                        'Server'          : SERVER_AMAZON_S3,
                        'Content-Type'    : 'application/xml',
                ]
                body = xml.toString()
            }

        }
        expect(httpStub)
    }

    private expect(HttpStub httpStub) {
        add(httpStub, stubAction(httpStub))
    }

    private HttpServer.ActionSupport stubAction(HttpStub httpStub) {
        new HttpServer.ActionSupport("Generic stub handler") {
            void handle(HttpServletRequest request, HttpServletResponse response) {
                httpStub.response?.headers?.each {
                    response.addHeader(it.key, it.value)
                }
                response.setStatus(httpStub.response.status)
                if (httpStub.response?.body) {
                    response.outputStream.bytes = httpStub.response.body
                }
            }
        }
    }

    private void add(HttpStub httpStub, HttpServer.ActionSupport action) {
        HttpServer.HttpExpectOne expectation = new HttpServer.HttpExpectOne(action, [httpStub.request.method], httpStub.request.path)
        expectations << expectation
        addHandler(new AbstractHandler() {
            void handle(String target, HttpServletRequest request, HttpServletResponse response, int dispatch) {
                if (requestMatches(httpStub, request)) {
                    assertRequest(httpStub, request)
                    if (expectation.run) {
                        println("This expectation for the request [${request.method} :${request.pathInfo}] was already handeled - skipping")
                        return
                    }
                    if (!((Request) request).isHandled()) {
                        expectation.run = true
                        action.handle(request, response)
                        ((Request) request).setHandled(true)
                    }
                }
            }
        })
    }


    private calculateEtag(File file) {
        MessageDigest digest = MessageDigest.getInstance("MD5")
        digest.update(file.bytes);
        new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
    }
}
