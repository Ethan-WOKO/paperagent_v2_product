package com.yanban.api.settings;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/model-providers")
public class SharedModelAdminController {
    private final SharedModelCatalog catalog;
    public SharedModelAdminController(SharedModelCatalog catalog) { this.catalog=catalog; }
    @GetMapping public List<SharedModelCatalog.ProviderView> list() { return catalog.providers(); }
    @PostMapping public long create(@Valid @RequestBody SharedModelCatalog.ProviderInput input) { return catalog.saveProvider(null,input); }
    @PutMapping("/{id}") public long update(@PathVariable long id,@Valid @RequestBody SharedModelCatalog.ProviderInput input) { return catalog.saveProvider(id,input); }
    @GetMapping("/{id}/models") public List<SharedModelCatalog.ModelView> models(@PathVariable long id) { return catalog.models(id); }
    @PutMapping("/{id}/models") public void save(@PathVariable long id,@Valid @RequestBody SharedModelCatalog.ModelInput input) { catalog.saveModel(id,input); }
    @PutMapping("/{id}/models/batch") public List<SharedModelCatalog.ModelView> saveAll(@PathVariable long id,@Valid @RequestBody SharedModelCatalog.ModelBatch input) { return catalog.saveModels(id,input); }
    @PostMapping("/{id}/sync") public List<SharedModelCatalog.ModelView> sync(@PathVariable long id) { return catalog.sync(id); }
}
