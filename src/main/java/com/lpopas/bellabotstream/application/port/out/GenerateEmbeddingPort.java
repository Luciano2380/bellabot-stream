package com.lpopas.bellabotstream.application.port.out;

import java.util.List;

public interface GenerateEmbeddingPort {

    void ingestWebsites(List<String> urls);
    void ingestProductCatalog(List<String> storeUrls);
    void ingestLocalPdf(String pathPdf);
}
