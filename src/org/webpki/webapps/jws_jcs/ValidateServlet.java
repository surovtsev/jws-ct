/*
 *  Copyright 2006-2019 WebPKI.org (http://webpki.org).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.webpki.webapps.jws_jcs;

import java.io.IOException;

import java.security.cert.X509Certificate;

import java.util.Vector;

import java.util.logging.Logger;

import javax.servlet.ServletException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.webpki.crypto.AlgorithmPreferences;
import org.webpki.crypto.CertificateInfo;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONOutputFormats;
import org.webpki.json.JSONParser;

import org.webpki.jose.jws.JwsHmacValidator;
import org.webpki.jose.jws.JwsValidator;
import org.webpki.jose.jws.JwsAsymSignatureValidator;
import org.webpki.jose.jws.JwsDecoder;

import org.webpki.util.Base64URL;
import org.webpki.util.DebugFormatter;
import org.webpki.util.PEMDecoder;

public class ValidateServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static Logger logger = Logger.getLogger(ValidateServlet.class.getName());

    // HTML form arguments
    static final String JWS_OBJECT         = "jws";

    static final String JWS_VALIDATION_KEY = "vkey";
    
    static final String JWS_SIGN_LABL      = "siglbl";
    
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        try {
            request.setCharacterEncoding("utf-8");
            if (!request.getContentType().startsWith("application/x-www-form-urlencoded")) {
                throw new IOException("Unexpected MIME type:" + request.getContentType());
            }
            logger.info("JSON Signature Verification Entered");
            // Get the two input data items
            String signedJsonObject = CreateServlet.getParameter(request, JWS_OBJECT);
            String validationKey = CreateServlet.getParameter(request, JWS_VALIDATION_KEY);
            String signatureLabel = CreateServlet.getParameter(request, JWS_SIGN_LABL);

            // Parse the JSON data
            JSONObjectReader parsedObject = JSONParser.parse(signedJsonObject);
            
            // Create a pretty-printed JSON object without canonicalization
            String prettySignature = parsedObject.serializeToString(JSONOutputFormats.PRETTY_HTML);
            Vector<String> tokens = 
                    new JSONTokenExtractor().getTokens(signedJsonObject);
            int fromIndex = 0;
            for (String token : tokens) {
                int start = prettySignature.indexOf("<span ", fromIndex);
                int stop = prettySignature.indexOf("</span>", start);
                // <span style="color:#C00000">
                prettySignature = prettySignature.substring(0, 
                                                            start + 28) + 
                                                              token + 
                                                              prettySignature.substring(stop);
                fromIndex = start + 1;
            }
            
            // Now begin the real work...
            
            // Note: some of the stuff here is unnecessary if you use
            // the JWS/CT validation method but it hides the data we need for
            // illustrating the function

            // Decode
            JwsDecoder jwsDecoder = new JwsDecoder(parsedObject, signatureLabel);

            // Get the embedded (detached) JWS signature
            String jwsString = parsedObject.getString(signatureLabel);
            
            // Remove the signature property
            parsedObject.removeProperty(signatureLabel);
            
            // Get the actual signed data.  Of course using RFC 8785 :)
            byte[] jwsPayload = parsedObject.serializeToBytes(JSONOutputFormats.CANONICALIZED);

            X509Certificate[] certificatePath = jwsDecoder.getOptionalCertificatePath();
            StringBuilder certificateData = null;
            if (certificatePath != null) {
                for (X509Certificate certificate : certificatePath) {
                    if (certificateData == null) {
                        certificateData = new StringBuilder();
                    } else {
                        certificateData.append("<br>&nbsp;<br>");
                    }
                    certificateData.append(
                        HTML.encode(new CertificateInfo(certificate).toString())
                            .replace("\n", "<br>").replace("  ", ""));
                }
            }
            
            // Recreate the validation key and validate the signature
            JwsValidator jwsValidator;
            boolean jwkValidationKey = validationKey.startsWith("{");
            if (jwsDecoder.getSignatureAlgorithm().isSymmetric()) {
                jwsValidator = new JwsHmacValidator(DebugFormatter.getByteArrayFromHex(validationKey));
            } else {
                jwsValidator = new JwsAsymSignatureValidator(jwkValidationKey ? 
                        JSONParser.parse(validationKey).getCorePublicKey(AlgorithmPreferences.JOSE)
                                                                              :
                        PEMDecoder.getPublicKey(validationKey.getBytes("utf-8")));
            }
            jwsValidator.validateSignature(jwsDecoder);
            StringBuilder html = new StringBuilder(
                    "<div class='header'> Signature Successfully Validated</div>")
                .append(HTML.fancyBox("signed", 
                                      prettySignature, 
                                      "JSON object signed by an embedded JWS element"))           
                .append(HTML.fancyBox("header", 
                                      jwsDecoder.getJwsProtectedHeader().serializeToString(
                                              JSONOutputFormats.PRETTY_HTML),
                                      "Decoded JWS header"))
                .append(HTML.fancyBox("vkey",
                                      jwkValidationKey ? 
                                          JSONParser.parse(validationKey)
                                              .serializeToString(JSONOutputFormats.PRETTY_HTML)
                                                       :
                                      HTML.encode(validationKey).replace("\n", "<br>"),
                                      "Signature validation " +
                                      (jwsDecoder.getSignatureAlgorithm().isSymmetric() ? 
                                             "secret key in hexadecimal" :
                                             "public key in " + 
                                             (jwkValidationKey ? "JWK" : "PEM") +
                                             " format")))
                .append(HTML.fancyBox("canonical", 
                                      HTML.encode(new String(jwsPayload, "utf-8")),
                                      "Canonical version of the JSON data " +
                                        "(with possible line breaks " +
                                        "for display purposes only)"));
            if (certificateData != null) {
                html.append(HTML.fancyBox("certpath", 
                                          certificateData.toString(),
                                          "Core certificate data"));
            }
            html.append(HTML.fancyBox("original", 
                                      new StringBuilder(jwsString)
                                        .insert(jwsString.indexOf('.') + 1, 
                                                Base64URL.encode(jwsPayload)).toString(),
          "Finally (as a reference only...), the same object expressed as a standard JWS"));

            // Finally, print it out
            HTML.standardPage(response, null, html.append("<div style='padding:10pt'></div>"));
        } catch (Exception e) {
            HTML.errorPage(response, e);
        }
    }

    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        HTML.standardPage(response, null, new StringBuilder(
                "<form name='shoot' method='POST' action='validate'>" +
                "<div class='header'>Testing JSON Signatures</div>")
            .append(HTML.fancyText(true,
                JWS_OBJECT,
                10, 
                HTML.encode(JWSJCSService.sampleSignature),
                "Paste a signed JSON object in the text box or try with the default"))
            .append(HTML.fancyText(true,
                JWS_VALIDATION_KEY,
                4, 
                HTML.encode(JWSJCSService.samplePublicKey),
                            "Validation key (secret key in hexadecimal or public " +
                              "key in PEM or &quot;plain&quot; JWK format)"))
            .append(HTML.fancyText(true,
                JWS_SIGN_LABL,
                1, 
                HTML.encode(CreateServlet.DEFAULT_SIG_LBL),
                "Anticipated signature label"))
            .append(
                "<div style='display:flex;justify-content:center'>" +
                "<div class='stdbtn' onclick=\"document.forms.shoot.submit()\">" +
                "Validate JSON Signature" +
                "</div>" +
                "</div>" +
                "</form>" +
                "<div>&nbsp;</div>"));
    }
}
