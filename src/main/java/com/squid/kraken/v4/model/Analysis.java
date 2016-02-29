package com.squid.kraken.v4.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.squid.kraken.v4.model.ProjectAnalysisJob.OrderBy;
import com.squid.kraken.v4.model.ProjectAnalysisJob.RollUp;

@JsonDeserialize(as = SimpleAnalysis.class)
public interface Analysis {

	public abstract String getDomain();

	public abstract void setDomain(String domain);

	public abstract List<String> getFacets();

	public abstract void setFacets(List<String> facets);

	public abstract List<String> getFilters();

	public abstract void setFilters(List<String> filters);

	public abstract List<OrderBy> getOrderBy();

	public abstract void setOrderBy(List<OrderBy> orderBy);

	public abstract List<RollUp> getRollups();

	public abstract void setRollups(List<RollUp> rollups);

	public abstract Long getOffset();

	public abstract void setOffset(Long offset);

	public abstract Long getLimit();

	public abstract void setLimit(Long limit);

}