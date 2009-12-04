/*
 * Copyright 2007-2009 the original author or authors.
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
package net.paoding.rose.web.instruction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import net.paoding.rose.web.Invocation;

/**
 * 
 * @author zhiliang.wang
 * 
 */
public class InputStreamInstruction extends AbstractInstruction {

    private int bufferSize = 1024;

    private InputStream inputStream;

    private String contentType;

    public InputStreamInstruction() {
    }

    public InputStreamInstruction(InputStream inputStream) {
        setInputStream(inputStream);
    }

    public InputStreamInstruction(InputStream inputStream, String contentType) {
        setInputStream(inputStream);
        setContentType(contentType);
    }

    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    public void setBufferSize(int bufferSize) {
        if (bufferSize > 0) {
            this.bufferSize = bufferSize;
        }
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    protected void doRender(Invocation inv) throws IOException, ServletException, Exception {
        InputStream inputStream = this.inputStream;
        if (inputStream == null) {
            return;
        }
        HttpServletResponse response = inv.getResponse();
        if (response.getContentType() == null) {
            String contentType = this.contentType == null ? "application/octet-stream"
                    : this.contentType;
            response.setContentType(contentType);
            if (logger.isDebugEnabled()) {
                logger.debug("set response.contentType by default:" + response.getContentType());
            }
        }
        try {
            byte[] buffer = new byte[bufferSize];
            int read;
            OutputStream out = null;
            while ((read = inputStream.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (out == null) {
                    out = inv.getResponse().getOutputStream();
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            inputStream.close();
        }
    }

}