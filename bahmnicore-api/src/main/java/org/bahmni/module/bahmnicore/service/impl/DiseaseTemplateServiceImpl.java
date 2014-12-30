package org.bahmni.module.bahmnicore.service.impl;

import org.bahmni.module.bahmnicore.contract.diseasetemplate.DiseaseTemplate;
import org.bahmni.module.bahmnicore.contract.diseasetemplate.DiseaseTemplateConfig;
import org.bahmni.module.bahmnicore.contract.diseasetemplate.DiseaseTemplatesConfig;
import org.bahmni.module.bahmnicore.contract.diseasetemplate.ObservationTemplate;
import org.bahmni.module.bahmnicore.mapper.ObservationTemplateMapper;
import org.bahmni.module.bahmnicore.service.BahmniObsService;
import org.bahmni.module.bahmnicore.service.BahmniVisitService;
import org.bahmni.module.bahmnicore.service.DiseaseTemplateService;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.Visit;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.bahmniemrapi.encountertransaction.contract.BahmniObservation;
import org.openmrs.module.bahmniemrapi.encountertransaction.mapper.OMRSObsToBahmniObsMapper;
import org.openmrs.module.emrapi.encounter.ConceptMapper;
import org.openmrs.module.emrapi.encounter.domain.EncounterTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Service
public class DiseaseTemplateServiceImpl implements DiseaseTemplateService {

    private BahmniObsService bahmniObsService;
    private BahmniVisitService bahmniVisitService;
    private ConceptService conceptService;
    private ConceptMapper conceptMapper;
    private ObservationTemplateMapper observationTemplateMapper;
    private OMRSObsToBahmniObsMapper omrsObsToBahmniObsMapper;

    @Autowired
    public DiseaseTemplateServiceImpl(BahmniObsService bahmniObsService, BahmniVisitService bahmniVisitService,
                                      ConceptService conceptService,
                                      OMRSObsToBahmniObsMapper omrsObsToBahmniObsMapper) {
        this.bahmniObsService = bahmniObsService;
        this.bahmniVisitService = bahmniVisitService;
        this.conceptService = conceptService;
        this.omrsObsToBahmniObsMapper = omrsObsToBahmniObsMapper;
        this.conceptMapper = new ConceptMapper();
        this.observationTemplateMapper = new ObservationTemplateMapper(conceptMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiseaseTemplate> allDiseaseTemplatesFor(DiseaseTemplatesConfig diseaseTemplatesConfig) {
        List<Concept> diseaseTemplateConcepts = getDiseaseTemplateConcepts();
        List<DiseaseTemplate> diseaseTemplates = new ArrayList<>();

        for (Concept diseaseTemplateConcept : diseaseTemplateConcepts) {
            DiseaseTemplate diseaseTemplate = getDiseaseTemplate(diseaseTemplatesConfig.getPatientUuid(), diseaseTemplateConcept);
            List<String> showOnlyConceptsForTheDisease = getShowOnlyConceptsForTheDisease(diseaseTemplate, diseaseTemplatesConfig);
            if (showOnlyConceptsForTheDisease.size() > 0) {
                filterObs(diseaseTemplate, showOnlyConceptsForTheDisease);
            }
            diseaseTemplates.add(diseaseTemplate);
        }

        return diseaseTemplates;
    }

    private List<String> getShowOnlyConceptsForTheDisease(DiseaseTemplate diseaseTemplate, DiseaseTemplatesConfig diseaseTemplatesConfig) {
        for (DiseaseTemplateConfig diseaseTemplateConfig : diseaseTemplatesConfig.getDiseaseTemplateConfigList()) {
            if (diseaseTemplateConfig.getTemplateName().equals(diseaseTemplate.getConcept().getName())) {
                return diseaseTemplateConfig.getShowOnly();
            }
        }
        return new ArrayList<>();
    }

    private void filterObs(DiseaseTemplate diseaseTemplate, List<String> showOnly) {
        List<ObservationTemplate> removableObservationTemplate = new ArrayList<>();
        for (ObservationTemplate observationTemplate : diseaseTemplate.getObservationTemplates()) {
            if (!isExists(observationTemplate.getConcept(), showOnly)) {
                filterObs(observationTemplate, showOnly);
                if (observationTemplate.getBahmniObservations().size() == 0) {
                    removableObservationTemplate.add(observationTemplate);
                }
            }
        }
        diseaseTemplate.getObservationTemplates().removeAll(removableObservationTemplate);
    }

    private void filterObs(ObservationTemplate observationTemplate, List<String> conceptNames) {
        Collection<BahmniObservation> removableObservation = new ArrayList<>();
        for (BahmniObservation bahmniObservation : observationTemplate.getBahmniObservations()) {
            if (!isExists(bahmniObservation.getConcept(), conceptNames)) {
                if (bahmniObservation.getGroupMembers().size() > 0) {
                    filterObsGroupMembers(bahmniObservation, conceptNames);
                }
                if (bahmniObservation.getGroupMembers().size() == 0) {
                    removableObservation.add(bahmniObservation);
                }
            }
        }
        observationTemplate.removeBahmniObservations(removableObservation);
    }

    private void filterObsGroupMembers(BahmniObservation parent, List<String> conceptNames) {
        Collection<BahmniObservation> removableObservation = new ArrayList<>();
        for (BahmniObservation bahmniObservation : parent.getGroupMembers()) {
            if (!isExists(bahmniObservation.getConcept(), conceptNames)) {
                if (bahmniObservation.getGroupMembers().size() > 0) {
                    filterObsGroupMembers(bahmniObservation, conceptNames);
                }
                if (bahmniObservation.getGroupMembers().size() == 0) {
                    removableObservation.add(bahmniObservation);
                }
            }
        }

        parent.removeGroupMembers(removableObservation);
    }

    private boolean isExists(EncounterTransaction.Concept concept, List<String> conceptNames) {
        return conceptNames.contains(concept.getName());
    }

    @Override
    @Transactional(readOnly = true)
    public DiseaseTemplate diseaseTemplateFor(String patientUUID, String diseaseName) {
        Concept diseaseTemplateConcept = conceptService.getConceptByName(diseaseName);
        DiseaseTemplate diseaseTemplate = new DiseaseTemplate(conceptMapper.map(diseaseTemplateConcept));
        List<Concept> observationTemplateConcepts = diseaseTemplateConcept.getSetMembers();
        for (Concept concept : observationTemplateConcepts) {
            Collection<BahmniObservation> observations = bahmniObsService.observationsFor(patientUUID, Arrays.asList(concept), null);
            List<ObservationTemplate> observationTemplates = observationTemplateMapper.map(observations, concept);
            diseaseTemplate.addObservationTemplates(observationTemplates);
        }

        return diseaseTemplate;
    }

    private DiseaseTemplate getDiseaseTemplate(String patientUuid, Concept diseaseTemplateConcept) {
        DiseaseTemplate diseaseTemplate = new DiseaseTemplate(conceptMapper.map(diseaseTemplateConcept));

        for (Concept concept : diseaseTemplateConcept.getSetMembers()) {
            Visit latestVisit = bahmniVisitService.getLatestVisit(patientUuid, concept.getName().getName());
            if (latestVisit != null) {
                diseaseTemplate.addObservationTemplate(getObservationTemplate(patientUuid, concept, latestVisit));
            }
        }

        return diseaseTemplate;
    }

    private ObservationTemplate getObservationTemplate(String patientUuid, Concept concept, Visit latestVisit) {
        Collection<BahmniObservation> observations = getLatestObsFor(patientUuid, concept, latestVisit.getVisitId());
        ObservationTemplate observationTemplate = new ObservationTemplate();
        observationTemplate.setVisitStartDate(latestVisit.getStartDatetime());
        observationTemplate.setConcept(conceptMapper.map(concept));
        observationTemplate.setBahmniObservations(observations);
        return observationTemplate;
    }

    private Collection<BahmniObservation> getLatestObsFor(String patientUuid, Concept concept, Integer visitId) {
        List<Obs> latestObsForConceptSet = bahmniObsService.getLatestObsForConceptSetByVisit(patientUuid, concept.getName(Context.getLocale()).getName(), visitId);
        return omrsObsToBahmniObsMapper.map(latestObsForConceptSet, Arrays.asList(concept));
    }

    private List<Concept> getDiseaseTemplateConcepts() {
        Concept concept = conceptService.getConceptByName(DiseaseTemplate.ALL_DISEASE_TEMPLATES);
        return concept.getSetMembers();
    }
}
