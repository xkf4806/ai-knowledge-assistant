package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {

    private String answer;
    private List<SourceRef> sources;

}
